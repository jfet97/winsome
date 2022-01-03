package client.RMI;

import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RemoteClient extends RemoteObject implements IRemoteClient {

  private String username = "";
  private Map<String, List<String>> followers = new HashMap<>();

  private RemoteClient(String username) {
    super();
    this.username = username;
  }

  @Override
  public void replaceFollowers(Map<String, List<String>> fs) throws RemoteException {
    if (fs != null)
      this.followers = fs;
  }

  @Override
  public void newFollower(String user, List<String> tags) throws RemoteException {
    if (user != null)
      this.followers.compute(user, (k, v) -> tags);
  }

  @Override
  public void deleteFollower(String user) throws RemoteException {
    if (user != null)
      this.followers.remove(user);
  }

  @Override
  public String getUsername() throws RemoteException {
    return username;
  }

  public Map<String, List<String>> getFollowers() throws RemoteException {
    return Collections.unmodifiableMap(followers);
  }

  public static RemoteClient of(String username) {
    return new RemoteClient(username);
  }

}
