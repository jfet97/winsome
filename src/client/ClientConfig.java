package client;

public class ClientConfig {
  public final Integer tcp_port = null;
  public final String server_ip = null;
  public final String auth_token_path = null;
  public final Integer remote_registry_port = null;
  public final String stub_name = null;

  public Boolean isValid() {
    return tcp_port != null && tcp_port != 0 &&
    server_ip != null && !server_ip.equals("") &&
    auth_token_path != null && !auth_token_path.equals("") &&
    remote_registry_port != null && remote_registry_port != 0 &&
    stub_name != null && !stub_name.equals("");
  }
}
