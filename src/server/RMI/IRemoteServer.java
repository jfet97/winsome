package server.RMI;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

import client.RMI.IRemoteClient;
import domain.user.User;
import io.vavr.control.Either;

public interface IRemoteServer extends Remote {
  Either<String, User> signUp(String username, String password, List<String> tags) throws RemoteException;

  public void registerFollowersCallback(IRemoteClient remoteClient) throws RemoteException;

  public void unregisterFollowersCallback(IRemoteClient remoteClient) throws RemoteException;
}
