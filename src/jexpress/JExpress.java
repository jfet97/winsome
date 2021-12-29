package jexpress;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import domain.error.Error;
import http.HttpRequest;
import http.HttpResponse;
import io.vavr.control.Either;
import jexpress.expressrouting.ExpressRoute;
import utils.TriConsumer;
import utils.Wrapper;
import utils.QuadriConsumer;

public class JExpress {

  private final String GET = "GET";
  private final String POST = "POST";
  private final String PUT = "PUT";
  private final String PATCH = "PATCH";
  private final String DELETE = "DELETE";

  private final Map<String, Map<ExpressRoute, TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>>>> routes = new HashMap<>();
  private final List<QuadriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>, Runnable>> globalMiddlewares = new LinkedList<>();

  public JExpress() {
    this.routes.put(GET, new HashMap<>());
    this.routes.put(POST, new HashMap<>());
    this.routes.put(PUT, new HashMap<>());
    this.routes.put(PATCH, new HashMap<>());
    this.routes.put(DELETE, new HashMap<>());
  }

  public static JExpress of() {
    return new JExpress();
  }

  // -------------------------------------------------
  // middlewares registration

  public void use(
      QuadriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>, Runnable> middleware) {
    // add at the end of the list
    this.globalMiddlewares.add(middleware);
  }

  // callback registration
  private void add(ExpressRoute route, String method,
      TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    this.routes.get(method).put(route, cb);
  }

  public void get(String route,
      TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    var jexpressRoute = new ExpressRoute(route);
    this.add(jexpressRoute, GET, cb);
  }

  public void post(String route,
      TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    var jexpressRoute = new ExpressRoute(route);
    this.add(jexpressRoute, POST, cb);
  }

  public void put(String route,
      TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    var jexpressRoute = new ExpressRoute(route);
    this.add(jexpressRoute, PUT, cb);
  }

  public void patch(String route,
      TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    var jexpressRoute = new ExpressRoute(route);
    this.add(jexpressRoute, PATCH, cb);
  }

  public void delete(String route,
      TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    var jexpressRoute = new ExpressRoute(route);
    this.add(jexpressRoute, DELETE, cb);
  }

  // -------------------------------------------------
  // requests handling

  private void runMiddlewares(HttpRequest request, Map<String, String> parametersFromPath,
      List<QuadriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>, Runnable>> middlewares,
      Integer index, Wrapper<Boolean> runRouteHandler, Wrapper<Either<String, HttpResponse>> resWrapper) {

    try {
      var middleware = middlewares.get(index);

      middleware.accept(request, parametersFromPath, eresponse -> {
        var response = eresponse.recoverWith(err -> HttpResponse
            .build(HttpResponse.HTTPV11, HttpResponse.CODE_500[0], HttpResponse.CODE_500[1])
            .flatMap(res -> res.setHeader("Content-Type", "application/json"))
            .flatMap(res -> res.setHeader("Content-Length", Error.of(err).toJSON().getBytes().length + ""))
            .flatMap(r -> r.setHeader("Connection", "keep-alive"))
            .flatMap(res -> res.setBody(Error.of(err).toJSON())));

        // set this response to be returned
        // (it will be returned unless a following middleware overwrites it)
        resWrapper.value = response;
      }, () -> runMiddlewares(request, parametersFromPath, middlewares, index + 1, runRouteHandler, resWrapper));

    } catch (Exception e) {
      // expect an IOOB exception when we run out of middlewares
      runRouteHandler.value = true;
    }

  }

  // this method is thread safe as long as the configuration process has finished
  public Either<String, HttpResponse> handle(HttpRequest request) {

    var resWrapper = Wrapper.<Either<String, HttpResponse>>of(null);

    if (request != null) {
      // get the handlers based on the request HTTP method
      var handlers = this.routes.get(request.getMethod());

      if (handlers != null) {
        // the used HTTP method is supported
        var target = request.getRequestTarget();
        var isThereHandler = handlers.keySet().stream().anyMatch(route -> route.matches(target));

        if (isThereHandler) {

          // there is a proper handler for the request target
          handlers.entrySet().forEach(entry -> {
            var route = entry.getKey();
            var handler = entry.getValue();

            if (route.matches(target)) {
              var parametersFromPath = route.getParametersFromPath(target);
              var runRouteHandler = Wrapper.of(false);

              // first: run middlewares
              runMiddlewares(request, parametersFromPath, this.globalMiddlewares, 0, runRouteHandler, resWrapper);

              // second: call the route handler only if the last middleware has called the
              // next callback
              if (runRouteHandler.value) {
                handler.accept(request, parametersFromPath, eresponse -> {
                  var response = eresponse.recoverWith(err -> HttpResponse
                      .build(HttpResponse.HTTPV11, HttpResponse.CODE_500[0], HttpResponse.CODE_500[1])
                      .flatMap(res -> res.setHeader("Content-Type", "application/json"))
                      .flatMap(r -> r.setHeader("Connection", "keep-alive"))
                      .flatMap(res -> res.setHeader("Content-Length", Error.of(err).toJSON().getBytes().length + ""))
                      .flatMap(res -> res.setBody(Error.of(err).toJSON())));

                  resWrapper.value = response;
                });
              }
            }
          });
        } else {

          // not found a proper handler for the request target
          var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.CODE_404[0], HttpResponse.CODE_404[1])
              .flatMap(res -> res.setHeader("Content-Type", "application/json"))
              .flatMap(r -> r.setHeader("Connection", "keep-alive"))
              .flatMap(res -> res.setHeader("Content-Length",
                  Error.of(target + " not found").toJSON().getBytes().length + ""))
              .flatMap(res -> res.setBody(Error.of(target + " not found").toJSON()));

          resWrapper.value = response;
        }

      } else {

        // this HTTP method is not supported
        var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.CODE_405[0], HttpResponse.CODE_405[1])
            .flatMap(res -> res.setHeader("Content-Type", "application/json"))
            .flatMap(r -> r.setHeader("Connection", "keep-alive"))
            .flatMap(res -> res.setHeader("Content-Length",
                Error.of(request.getMethod() + " not supported").toJSON().getBytes().length + ""))
            .flatMap(res -> res.setBody(Error.of(request.getMethod() + " not supported").toJSON()));

        resWrapper.value = response;
      }
    }

    return resWrapper.value;
  }
}
