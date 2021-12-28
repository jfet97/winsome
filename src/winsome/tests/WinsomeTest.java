package winsome.tests;

import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.vavr.control.Either;
import winsome.Winsome;

public class WinsomeTest {

  private <E> Either<E, Void> sleep(Long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
    }

    return Either.right(null);
  }

  private <E, A> Either<E, A> tap(A x) {
    System.out.println(x);

    return Either.right(x);
  }

  @Test
  public void test1() throws InterruptedException {

    var winsome = new Winsome();

    var walletThread = new Thread(winsome.makeWalletRunnable(2000L, 70).get());
    var persistenceThread = new Thread(winsome.makePersistenceRunnable(1000L,
        "/Volumes/PortableSSD/MacMini/UniPi/Reti/Winsome/src/winsome/tests/WinsomeTest.json", false).get());

    var user1 = new Thread(() -> {

      var username = "Daniel";
      var password = "mom1956";

      winsome
          .register(username, password, Arrays.asList("sport", "music", "wine"))
          .flatMap(__ -> sleep(1000L))
          .flatMap(__ -> winsome.login(username, password))
          .flatMap(this::tap)
          .flatMap(__ -> sleep(500L))
          .flatMap(__ -> winsome.createPost(username, "Shared Reference",
              "Some sources highlight that Stream.of(…).collect(…) may have a larger memory and performance footprint than Arrays.asList(). But in almost all cases, it's such a micro-optimization that there is little difference."))
          .flatMap(__ -> sleep(1000L))
          .flatMap(__ -> winsome.logout(username))
          .swap()
          .forEach(System.out::println);

    });
    var user2 = new Thread(() -> {

      var username = "Mary";
      var password = "itsucks";

      winsome
          .register(username, password, Arrays.asList("music", "love", "dance", "fruit"))
          .flatMap(__ -> sleep(500L))
          .flatMap(__ -> winsome.login(username, password))
          .flatMap(this::tap)
          .flatMap(__ -> sleep(2000L))
          .flatMap(__ -> winsome.listUsers(username))
          .flatMap(us -> {
            // expected Daniel inside us because there is a common tag: music
            assertTrue(us.isEmpty() == false);
            var daniel = us.get(0);
            return winsome.followUser(username, daniel);
          })
          .flatMap(__ -> sleep(500L))
          .flatMap(__ -> winsome.showFeed(username))
          .flatMap(ps -> {
            // expected Daniel's post
            assertTrue(ps.isEmpty() == false);
            var p = ps.get(0);
            return winsome
                .addComment(username, p.author, p.uuid, "Nice post Daniel")
                .flatMap(__ -> winsome.ratePost(username, p.author, p.uuid, true));
          })
          .flatMap(__ -> sleep(1000L))
          .flatMap(__ -> winsome.logout(username))
          .swap()
          .forEach(System.out::println);
    });

    var user3 = new Thread(() -> {

      var username = "Topolino";
      var password = "Hackerino";

      winsome
          .register(username, password, Arrays.asList("music", "love"))
          .flatMap(__ -> sleep(500L))
          .flatMap(__ -> winsome.login(username, password))
          .flatMap(this::tap)
          .flatMap(__ -> sleep(3000L))
          .flatMap(__ -> winsome.listUsers(username))
          .flatMap(us -> {
            // expected Mary and Daniel inside us because there is a common tag: music
            assertTrue(us.isEmpty() == false);

            return Either
                .sequenceRight(
                    us.stream()
                        .map(u -> winsome.followUser(username, u))
                        .collect(Collectors.toList()));

          })
          .flatMap(__ -> sleep(5000L))
          .flatMap(__ -> winsome.showFeed(username))
          .flatMap(ps -> {
            // expected Daniel's post
            assertTrue(ps.isEmpty() == false);
            var p = ps.get(0);
            return winsome
                .addComment(username, p.author, p.uuid, "It sucks Daniel")
                .flatMap(__ -> winsome.ratePost(username, p.author, p.uuid, false));
          })
          .flatMap(__ -> sleep(3000L))
          .flatMap(__ -> winsome.logout(username))
          .swap()
          .forEach(System.out::println);
    });

    walletThread.start();
    persistenceThread.start();
    user1.start();
    user2.start();
    user3.start();

    user1.join();
    user2.join();
    user3.join();
    walletThread.interrupt();
    persistenceThread.interrupt();

    walletThread.join();
    persistenceThread.join();
  }
}
