package domain.jwt;

import java.util.Calendar;
import java.util.Date;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;

import domain.user.User;
import io.vavr.control.Either;
import secrets.Secrets;

public class WinsomeJWT {

  private WinsomeJWT(String jwt) {
  }

  public static String createJWT(String secret, String username) {
    var algorithm = Algorithm.HMAC256(secret);

    var cal = Calendar.getInstance();
    cal.setTimeInMillis(new Date().getTime());
    cal.add(Calendar.DATE, 1);

    var jwt = JWT.create()
        .withIssuer("winsome-asc")
        .withExpiresAt(cal.getTime())
        .withClaim("username", username)
        .sign(algorithm);

    return jwt;
  }

  public static Either<String, User> validateJWT(String jwt) {

    var toRet = Either.<String, User>right(null);
    try {
      var verifier = JWT.require(Algorithm.HMAC256(Secrets.JWT_SIGN_SECRET))
          .withIssuer("winsome-asc")
          .withClaimPresence("username")
          .build();

      var dec = verifier.verify(jwt);

      var usernameClaim = dec.getClaim("username");

      if (usernameClaim.isNull()) {
        throw new RuntimeException();
      }

      toRet = Either.right(User.of(usernameClaim.asString(), "INVALD_USER", null));

    } catch (JWTVerificationException e) {
      // Invalid signature/claims e.g. token expired
      // reply accordingly
      toRet = Either.left("invalid auth token");
    } catch (Exception e) {
      // reply accordingly
      toRet = Either.left("invalid auth token");
    }

    return toRet;
  }

  public static Either<String, String> wrapWithMessageJSON(String jwt, String message) {
    if (jwt == null || message == "") {
      return Either.left("cannoto wrap jwt because of null arguments");
    } else {
      return Either.right("{\"jwt\":\"" + jwt + "\",\"message\":\"" + message + "\"}");
    }
  }

}
