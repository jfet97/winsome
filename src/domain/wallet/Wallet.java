package domain.wallet;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.vavr.control.Either;

public class Wallet {

  // ConcurrentMap<username, transactions>
  @JsonProperty("wallet")
  private final ConcurrentMap<String, List<WalletTransaction>> wallet = new ConcurrentHashMap<>();

  // last time the wallet thread has run
  @JsonProperty("prevTimestamp")
  private Long prevTimestamp = new Date().getTime(); // needs manual synchronization (wallet and persistence threads)

  public static Wallet of() {
    return new Wallet();
  }

  private <T> Either<String, T> nullGuard(T x, String name) {
    if (x == null) {
      return Either.left(name + " cannot be null");
    } else {
      return Either.right(x);
    }
  }

  public void setPrevTimestamp(Long value) {
    synchronized (this) {
      this.prevTimestamp = value;
    }
  }

  public Long getPrevTimestamp() {
    synchronized (this) {
      return this.prevTimestamp;
    }
  }

  // return a deep copy of the wallet of an user
  public Either<String, List<WalletTransaction>> getWalletOf(String username) {
    return nullGuard(username, "username")
        .map(__ -> this.wallet.get(username))
        .flatMap(ts -> {
          if (ts != null) {

            // sync with addTransaction
            synchronized (ts) {
              return Either.right(ts
                  .stream()
                  .map(WalletTransaction::clone)
                  .collect(Collectors.toList()));
            }
            // by returning a clone, we can safely perform
            // further actions on the list without the need
            // of the lock, although "the read may not get the latest write"
            // (eventual consistency)

          } else {
            return Either.left("unknown user");
          }
        });

  }

  // add a user to the wallet
  public Either<String, Void> addUser(String username) {
    // does not happen so often
    return nullGuard(username, "username")
        .map(__ -> this.wallet.computeIfAbsent(username, k -> new LinkedList<WalletTransaction>()))
        .flatMap(__ -> Either.<String, Void>right(null));
  }

  // add a transaction to the wallet, given a specific user and a gain
  public Either<String, WalletTransaction> addTransaction(String username, Double gain) {
    // only the wallet thread will add transactions
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(gain, "gain"))
        .flatMap(__ -> {
          var ts = this.wallet.get(username);
          return ts == null ? Either.left("unknown user") : Either.right(ts);
        })
        .flatMap(ts -> {
          var et = WalletTransactionFactory.create(gain)
              .toEither();

          // sync with getWalletOf
          synchronized (ts) {
            et.forEach(ts::add);
          }

          return et;
        });
  }

  public String toJSON() {

    var walletLine = "\"wallet\":{";
    walletLine += this.wallet.entrySet()
        .stream()
        .map(e -> {
          var toRet = "\"" + e.getKey() + "\":[";

          toRet += e.getValue().stream().map(t -> t.toJSON()).reduce("",
              (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);

          return toRet + "]";
        })
        .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    walletLine += "}";

    return String.join("",
        "{",
        "\"prevTimestamp\":" + getPrevTimestamp() + ",",
        walletLine,
        "}");
  }
}
