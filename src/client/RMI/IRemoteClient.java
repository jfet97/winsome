package client.RMI;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.Set;

public interface IRemoteClient extends Remote {
  public void newFollower(String user) throws RemoteException;

  public void deleteFollower(String user) throws RemoteException;

  public void replaceFollowers(Set<String> fs) throws RemoteException;

  public String getUsername() throws RemoteException;
}
