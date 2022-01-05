package domain.wallet;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.vavr.control.Either;

public class Wallet {

  // ConcurrentMap<username, transactions>
  private final ConcurrentMap<String, List<WalletTransaction>> wallet = new ConcurrentHashMap<>();

  // last time the wallet thread has run
  public Long prevTimestamp = new Date().getTime(); // needs manual synchronization (wallet and persistence threads)

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

  // return an immutable copy of the concurrent map
  public Map<String, List<WalletTransaction>> getWallet() {
    return Collections.unmodifiableMap(this.wallet);
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

          et.forEach(ts::add);
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
