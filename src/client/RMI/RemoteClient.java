package client.RMI;

import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;
import java.util.HashSet;
import java.util.Set;

public class RemoteClient extends RemoteObject implements IRemoteClient {

  String username = "";
  Set<String> followers = new HashSet<>();

  private RemoteClient(String username) {
    super();
    this.username = username;
  }

  @Override
  public void replaceFollowers(Set<String> fs) throws RemoteException {
    if (fs != null)
      this.followers = fs;
  }

  @Override
  public void newFollower(String user) throws RemoteException {
    if (user != null)
      this.followers.add(user);
  }

  @Override
  public void deleteFollower(String user) throws RemoteException {
    if (user != null)
      this.followers.remove(user);
  }

  @Override
  public String getUsername() throws RemoteException {
    return null;
  }

  public static RemoteClient of(String username) {
    return new RemoteClient(username);
  }

}
