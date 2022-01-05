package client.RMI;

import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class RemoteClient extends RemoteObject implements IRemoteClient {

  private String username = "";
  private Map<String, List<String>> followers = new ConcurrentHashMap<>();

  private RemoteClient(String username) {
    super();
    this.username = username;
  }

  @Override
  public void replaceFollowers(Map<String, List<String>> fs) throws RemoteException {
    this.followers = fs
        .entrySet()
        .stream()
        .collect(Collectors.toConcurrentMap(e -> e.getKey(), e -> e.getValue()));
  }

  @Override
  public void newFollower(String user, List<String> tags) throws RemoteException {
    if (user != null) {
      this.followers.compute(user, (k, v) -> tags);
    }

  }

  @Override
  public void deleteFollower(String user) throws RemoteException {
    if (user != null) {
      this.followers.compute(user, (k, v) -> null);
    }
  }

  @Override
  public String getUsername() throws RemoteException {
    return username;
  }

  // not available to the server
  public Map<String, List<String>> getFollowers() {
    // return an immutable version of the followers hashmap
    return Collections.unmodifiableMap(followers);
  }

  public static RemoteClient of(String username) {
    return new RemoteClient(username);
  }

}
