package jexpress.expressrouting.tests;

import org.junit.jupiter.api.Test;

import jexpress.expressrouting.ExpressRoute;

public class ExpressRoutingTest {

  @Test
  public void test() {
    var routeDefinition = "/:commoditySlug/options/:optionId";
    var path = "/porkbelly/options/1234";

    var route = new ExpressRoute(routeDefinition);

    if (route.matches(path)) {
      var parametersFromPath = route.getParametersFromPath(path);
      System.out.println(parametersFromPath);
    }
  }

}
