package utils;

// a generic triple
public class Triple<A, B, C> {

  private final A fst;
  private final B snd;
  private final C trd;

  public A fst() {
    return fst;
  }

  public Triple<A, B, C> fst(A fst) {
    return new Triple<A, B, C>(fst, this.snd, this.trd);
  }

  public B snd() {
    return snd;
  }

  public Triple<A, B, C> snd(B snd) {
    return new Triple<A, B, C>(this.fst, snd, this.trd);
  }

  public C trd() {
    return trd;
  }

  public Triple<A, B, C> trd(C trd) {
    return new Triple<A, B, C>(this.fst, this.snd, trd);
  }

  public static <A, B, C> Triple<A, B, C> of(A fst, B snd, C trd) {
    return new Triple<A, B, C>(fst, snd, trd);
  }

  public Triple(A fst, B snd, C trd) {
    this.fst = fst;
    this.snd = snd;
    this.trd = trd;
  }
}
