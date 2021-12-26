package utils;

import java.util.Objects;

@FunctionalInterface
public interface QuadriConsumer<T, U, V, S> {

  /**
   * Performs this operation on the given arguments.
   *
   * @param t the first input argument
   * @param u the second input argument
   * @param v the third input argument
   */
  void accept(T t, U u, V v, S s);

  /**
   * Returns a composed {@code QuadriConsumer} that performs, in sequence, this
   * operation followed by the {@code after} operation. If performing either
   * operation throws an exception, it is relayed to the caller of the
   * composed operation. If performing this operation throws an exception,
   * the {@code after} operation will not be performed.
   *
   * @param after the operation to perform after this operation
   * @return a composed {@code QuadriConsumer} that performs in sequence this
   *         operation followed by the {@code after} operation
   * @throws NullPointerException if {@code after} is null
   */
  default QuadriConsumer<T, U, V, S> andThen(QuadriConsumer<? super T, ? super U, ? super V, ? super S> after) {
    Objects.requireNonNull(after);

    return (t, u, v, s) -> {
      accept(t, u, v, s);
      after.accept(t, u, v, s);
    };
  }
}