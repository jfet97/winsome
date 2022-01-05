package server.RMI;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

import client.RMI.IRemoteClient;
import io.vavr.control.Either;

public interface IRemoteServer extends Remote {
  // to register a new user
  Either<String, String> signUp(String username, String password, List<String> tags) throws RemoteException;

  // to receive updates about the followers set of an user
  public void registerFollowersCallback(IRemoteClient remoteClient) throws RemoteException;

  // stop to receive those updates
  public void unregisterFollowersCallback(IRemoteClient remoteClient) throws RemoteException;
}
