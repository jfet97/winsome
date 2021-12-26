package jexpress;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import http.HttpRequest;
import http.HttpResponse;
import io.vavr.control.Either;
import jexpress.expressrouting.ExpressRoute;
import utils.TriConsumer;

public class JExpress {

  // TODO: needs a TCP connection to send responses as constructor parameter?

  private final String GET = "GET";
  private final String POST = "POST";
  private final String PUT = "PUT";
  private final String PATCH = "PATCH";
  private final String DELETE = "DELETE";

  private final Map<String, Map<ExpressRoute, TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>>>> routes = new HashMap<>();

  public JExpress() {
    this.routes.put(GET, new HashMap<>());
    this.routes.put(POST, new HashMap<>());
    this.routes.put(PUT, new HashMap<>());
    this.routes.put(PATCH, new HashMap<>());
    this.routes.put(DELETE, new HashMap<>());
  }

  // callback registration
  private void add(ExpressRoute route, String method,
      TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    this.routes.get(method).put(route, cb);
  }

  public void get(String route, TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    var jexpressRoute = new ExpressRoute(route);
    this.add(jexpressRoute, GET, cb);
  }

  public void post(String route, TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    var jexpressRoute = new ExpressRoute(route);
    this.add(jexpressRoute, POST, cb);
  }

  public void put(String route, TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    var jexpressRoute = new ExpressRoute(route);
    this.add(jexpressRoute, PUT, cb);
  }

  public void patch(String route, TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    var jexpressRoute = new ExpressRoute(route);
    this.add(jexpressRoute, PATCH, cb);
  }

  public void delete(String route, TriConsumer<HttpRequest, Map<String, String>, Consumer<Either<String, HttpResponse>>> cb) {
    var jexpressRoute = new ExpressRoute(route);
    this.add(jexpressRoute, DELETE, cb);
  }

  // request handling
  public void handle(HttpRequest request) {
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

              handler.accept(request, parametersFromPath, eresponse -> {
                var response = eresponse.getOrElseGet(err -> HttpResponse
                    .build(HttpResponse.HTTPV11, HttpResponse.CODE_500[0], HttpResponse.CODE_500[1])
                    .flatMap(res -> res.setHeader("Content-Type", "text/plain; charset=UTF-8"))
                    .flatMap(res -> res.setBody(err))
                    .get());

                // send this response
              });
            }
          });
        } else {

          // not found a proper handler for the request target
          var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.CODE_404[0], HttpResponse.CODE_404[1])
              .flatMap(res -> res.setHeader("Content-Type", "text/plain; charset=UTF-8"))
              .flatMap(res -> res.setBody(target + " not found"))
              .get();

          // send this response
        }

      } else {

        // this HTTP method is not supported
        var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.CODE_405[0], HttpResponse.CODE_405[1])
            .flatMap(res -> res.setHeader("Content-Type", "text/plain; charset=UTF-8"))
            .flatMap(res -> res.setBody(request.getMethod() + " not supported"))
            .get();

        // send this response
      }
    }
  }
}
