package domain.pojos.wallet.tests;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import domain.pojos.wallet.Wallet;

public class WalletTest {
  @Test
  public void testJson() throws JsonMappingException, JsonProcessingException, NoSuchFieldException, SecurityException,
      IllegalArgumentException, IllegalAccessException {

    var wallet = Wallet.of();

    assertTrue(wallet.addUser("user1").isRight());
    assertTrue(wallet.addUser("user2").isRight());

    assertTrue(wallet.addTransaction("user1", 2.6).isRight());
    assertTrue(wallet.addTransaction("user1", 4.8).isRight());
    assertTrue(wallet.addTransaction("user2", 9.5).isRight());

    System.out.println(wallet.toJSON());

    var objectMapper = new ObjectMapper();
    objectMapper.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    assertDoesNotThrow(() -> objectMapper.readTree(wallet.toJSON()));

    // parsing
    var walletFromJSON = objectMapper.readValue(wallet.toJSON(), Wallet.class);
    String indented = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(walletFromJSON);
    System.out.println(indented);

    var walletField = Wallet.class.getDeclaredField("wallet");
    walletField.setAccessible(true);
    var walletInternalField = walletField.get(walletFromJSON);

    assertTrue(walletInternalField instanceof ConcurrentMap<?, ?>);
  }
}
