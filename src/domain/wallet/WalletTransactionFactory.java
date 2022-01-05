package domain.wallet;

import io.vavr.control.Validation;

public class WalletTransactionFactory {

  private WalletTransactionFactory() {
  }

  private static interface WalletTransactionValidator {
    public Validation<String, WalletTransaction> validateWalletTransaction(Double gain);
  }

  private static WalletTransactionValidator validator = new WalletTransactionValidator() {
    @Override
    // validate a transaction
    public Validation<String, WalletTransaction> validateWalletTransaction(Double gain) {
      return validateGain(gain).map(WalletTransaction::of);
    }

    // validate the gain
    private Validation<String, Double> validateGain(Double gain) {
      var errorMessage = "";

      if (gain == null)
        errorMessage = "gain cannot be null";

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(gain);
    }

  };

  // try to create a WalletTransaction instance, collect each error if any
  public static Validation<String, WalletTransaction> create(Double gain) {
    return validator.validateWalletTransaction(gain);
  }
}
