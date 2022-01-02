package server;

public class ServerConfig {
  public final Integer tcp_port = null;
  public final Integer remote_registry_port = null;
  public final Integer udp_port = null;
  public final Integer multicast_port = null;
  public final String multicast_ip = null;
  public final String server_ip = null;
  public final String persistence_path = null;
  public final Integer author_percentage = null;
  public final Long persistence_interval = null;
  public final Long wallet_interval = null;
  public final String stub_name = null;
  public final String jwt_secret = null;

  public Boolean isValid() {
    return tcp_port != null && tcp_port != 0 &&
        remote_registry_port != null && remote_registry_port != 0 &&
        udp_port != null && udp_port != 0 &&
        multicast_port != null && multicast_port != 0 &&
        multicast_ip != null && !multicast_ip.equals("") &&
        server_ip != null && !server_ip.equals("") &&
        persistence_path != null && !persistence_path.equals("") &&
        author_percentage != null && author_percentage != 0 &&
        persistence_interval != null && persistence_interval != 0 &&
        wallet_interval != null && wallet_interval != 0 &&
        stub_name != null && !stub_name.equals("") &&
        jwt_secret != null && !jwt_secret.equals("");
  }
}
