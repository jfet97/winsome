package utils;

public class Pair<F, S> {

  private final F fst;
  private final S snd;

  public F fst() {
    return fst;
  }

  public Pair<F, S> fst(F fst) {
    return new Pair<F, S>(fst, this.snd);
  }

  public S snd() {
    return snd;
  }

  public Pair<F, S> snd(S snd) {
    return new Pair<F, S>(this.fst, snd);
  }

  public static <F, S> Pair<F, S> of(F fst, S snd) {
    return new Pair<F, S>(fst, snd);
  }

  public Pair(F fst, S snd) {
    this.fst = fst;
    this.snd = snd;
  }
}