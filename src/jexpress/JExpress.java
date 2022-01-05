package jexpress;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import domain.feedback.Feedback;
import http.HttpConstants;
import http.HttpRequest;
import http.HttpResponse;
import io.vavr.control.Either;
import jexpress.expressrouting.ExpressRoute;
import utils.TriConsumer;
import utils.Wrapper;
import utils.QuadriConsumer;
import utils.ToJSON;

public class JExpress {

  private final String GET = HttpConstants.GET;
  private final String POST = HttpConstants.POST;
  private final String PUT = HttpConstants.PUT;
  private final String PATCH = HttpConstants.PATCH;
  private final String DELETE = HttpConstants.DELETE;
  private final String OPTIONS = HttpConstants.OPTIONS;

  // routes handlers
  private final Map<String, Map<ExpressRoute, TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>>>> routes = new HashMap<>();
  // global middlewares that act before the above handlers
  private final List<QuadriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>, Runnable>> globalMiddlewares = new LinkedList<>();

  public JExpress() {
    this.routes.put(GET, new HashMap<>());
    this.routes.put(POST, new HashMap<>());
    this.routes.put(PUT, new HashMap<>());
    this.routes.put(PATCH, new HashMap<>());
    this.routes.put(DELETE, new HashMap<>());
    this.routes.put(OPTIONS, new HashMap<>());
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

  // handlers registration
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

  public void options(String route,
      TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    var jexpressRoute = new ExpressRoute(route);
    this.add(jexpressRoute, OPTIONS, cb);
  }

  // -------------------------------------------------
  // requests handling

  // run the middleware in order
  // a middleware run only if it is the first or the previous middleware has
  // called the next callback
  private void runMiddlewares(HttpRequest request, Map<String, String> parametersFromPath,
      List<QuadriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>, Runnable>> middlewares,
      Integer index, Wrapper<Boolean> runRouteHandler, Wrapper<Either<String, HttpResponse>> resWrapper) {

    try {
      var middleware = middlewares.get(index);

      middleware.accept(request, parametersFromPath, eresponse -> {
        var response = eresponse.recoverWith(err -> HttpResponse.build500(
            Feedback.error(
                ToJSON.toJSON(err)).toJSON(),
            HttpConstants.MIME_APPLICATION_JSON, true));

        // set this response to be returned
        // (it will be returned unless a following middleware overwrites it)
        resWrapper.value = response;
        // the next callback
      }, () -> runMiddlewares(request, parametersFromPath, middlewares, index + 1, runRouteHandler, resWrapper));

    } catch (IndexOutOfBoundsException e) {
      // expect an IOOB exception when we run out of middlewares
      runRouteHandler.value = true;
    } catch (Exception e) {
      e.printStackTrace();
    }

  }

  // this method is thread safe as long as the configuration process has finished
  public Either<String, HttpResponse> handle(HttpRequest request) {

    var resWrapper = Wrapper.<Either<String, HttpResponse>>of(null);

    if (request != null) {
      // get the handlers based on the request HTTP method
      var method = request.getMethod();
      var handlers = this.routes.get(method);

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
              // extract query parameters
              var parametersFromPath = route.getParametersFromPath(target);
              var runRouteHandler = Wrapper.of(false);

              // first: run middlewares
              runMiddlewares(request, parametersFromPath, this.globalMiddlewares, 0, runRouteHandler, resWrapper);

              // second: call the route handler only if the last middleware has called the
              // next callback
              if (runRouteHandler.value) {
                handler.accept(request, parametersFromPath, eresponse -> {
                  // if something went wrong, return a 500
                  var response = eresponse
                      .recoverWith(err -> HttpResponse.build500(
                          Feedback.error(
                              ToJSON.toJSON(err)).toJSON(),
                          HttpConstants.MIME_APPLICATION_JSON, true));
                  resWrapper.value = response;
                });
              }
            }
          });
        } else {

          // not found a proper handler for the request target
          var response = HttpResponse.build404(
              Feedback.error(
                  ToJSON.toJSON(method + " is not supported for route " + target)).toJSON(),
              HttpConstants.MIME_APPLICATION_JSON, true);

          resWrapper.value = response;
        }

      } else {

        // this HTTP method is not supported
        var response = HttpResponse.build404(
            Feedback.error(
                ToJSON.toJSON(method + "is not supported")).toJSON(),
            HttpConstants.MIME_APPLICATION_JSON, true);

        resWrapper.value = response;
      }
    }

    return resWrapper.value;
  }
}
