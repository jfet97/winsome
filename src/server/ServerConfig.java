package server;

public class ServerConfig {
  public Integer tcp_port;
  public Integer remote_registry_port;
  public Integer udp_port;
  public Integer multicast_port;
  public String multicast_ip;
  public String server_ip;
  public String persistence_path;
  public Integer author_percentage;
  public Long persistence_interval;
  public Long wallet_interval;
  public String stub_name;

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
        stub_name != null && !stub_name.equals("");
  }
}
