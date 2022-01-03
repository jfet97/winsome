package client.RMI;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Map;

public interface IRemoteClient extends Remote {
  public void replaceFollowers(Map<String, List<String>> fs) throws RemoteException;

  public void newFollower(String user, List<String> tags) throws RemoteException;

  public void deleteFollower(String user) throws RemoteException;

  public String getUsername() throws RemoteException;
}
