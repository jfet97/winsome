package winsome.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.function.Function;
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

  private <E, A> Either<E, A> tap(A x, Function<A, String> stringifier) {
    System.out.println(stringifier.apply(x));

    return Either.right(x);
  }

  @Test
  public void test1() throws InterruptedException {

    var winsome = new Winsome();

    var walletThread = new Thread(winsome.makeWalletRunnable(2000L, 70).get());
    var persistenceThread = new Thread(winsome.makePersistenceRunnable(500L,
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
          .flatMap(__ -> sleep(8000L))
          .flatMap(__ -> winsome.viewBlog(username))
          .flatMap(ps -> {
            // expect Daniel own post
            assertFalse(ps.isEmpty());
            assertTrue(ps.get(0).author.equals(username));
            return winsome.showPost(username, username, ps.get(0).uuid);
          })
          .flatMap(p -> tap(p, x -> x.toJSON()))
          .flatMap(__ -> sleep(5000L))
          .flatMap(__ -> winsome.listFollowers(username)
              .flatMap(fs -> {
                assertTrue(fs.size() == 1);
                assertEquals(fs.get(0), "Mary");
                return winsome.listFollowing(username)
                    .flatMap(fg -> {
                      assertTrue(fg.size() == 0);
                      return sleep(0L);
                    });
              }))
          .flatMap(__ -> sleep(1000L))
          .flatMap(__ -> winsome.createPost(username, "Topolino sucks", "Change my mind"))
          .flatMap(__ -> winsome.createPost(username, "Topolino sucks", "Change my mind"))
          .flatMap(p -> winsome.deletePost(username, p.uuid))
          .flatMap(__ -> sleep(1000L))
          .flatMap(__ -> winsome.logout(username))
          .swap()
          .forEach(System.out::println);

    });
    var user2 = new Thread(() -> {

      var username = "Mary";
      var password = "hello1234";

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
                .flatMap(__ -> winsome.ratePost(username, p.author, p.uuid, true))
                .flatMap(__ -> winsome.rewinPost(username, p.author, p.uuid));
          })
          .flatMap(__ -> sleep(3000L))
          .flatMap(__ -> winsome.listFollowers(username)
              .flatMap(fs -> {
                assertTrue(fs.size() == 1);
                assertEquals(fs.get(0), "Topolino");
                return winsome.listFollowing(username)
                    .flatMap(fg -> {
                      assertTrue(fg.size() == 1);
                      assertEquals(fg.get(0), "Daniel");
                      return sleep(0L);
                    });
              }))
          .flatMap(__ -> sleep(9500L))
          .flatMap(__ -> winsome.createPost(username, "Nature",
              "I am sitting on my balcony. It is spring and there is a little bit of heat in the sun. The balcony looks out over a road. The road is usually busy… an endless stream of trucks and cars but right now there is no traffic. Everyone is self-isolating. The machine has stopped. It feels strange. Peaceful. I can hear different birds… the wren, the blackbird, the robin. A blue tit is flitting from one branch to the next. Life goes on. I could sit here all day."))
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
          .flatMap(__ -> sleep(3000L))
          .flatMap(__ -> winsome.showFeed(username))
          .flatMap(ps -> {
            // expected Daniel's post
            assertTrue(ps.isEmpty() == false);
            var p = ps.get(0);

            return winsome
                .deletePost(username, p.uuid)
                .recover(e -> {
                  System.out.println(e);
                  return p;
                })
                .flatMap(__ -> winsome.addComment(username, p.author, p.uuid, "It sucks Daniel"))
                .flatMap(__ -> winsome.ratePost(username, p.author, p.uuid, false))
                .flatMap(__ -> winsome.unfollowUser(username, "Daniel"))
                .flatMap(__ -> winsome.rewinPost(username, p.author, p.uuid))
                .recover(e -> {
                  System.out.println(e);
                  return p;
                });
          })
          .flatMap(__ -> sleep(500L))
          .flatMap(__ -> winsome.listFollowers(username)
              .flatMap(fs -> {
                assertTrue(fs.size() == 0);
                return winsome.listFollowing(username)
                    .flatMap(fg -> {
                      assertTrue(fg.size() == 1);
                      assertEquals(fg.get(0), "Mary");
                      return sleep(0L);
                    });
              }))
          .flatMap(__ -> sleep(3000L))
          .flatMap(__ -> winsome.logout(username))
          // .flatMap(__ -> winsome.unfollowUser(username, "Daniel")) // user Topolino is
          // not logged
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

    Thread.sleep(3000); // persistence + wallet
    walletThread.interrupt();
    persistenceThread.interrupt();

    walletThread.join();
    persistenceThread.join();
  }
}
