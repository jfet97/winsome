package server.RMI;

import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import client.RMI.IRemoteClient;
import domain.user.User;
import io.vavr.control.Either;
import winsome.Winsome;

public class RemoteServer extends RemoteObject implements IRemoteServer {

  Winsome winsome;
  private ConcurrentMap<String, IRemoteClient> remotes = new ConcurrentHashMap<>();

  private RemoteServer(Winsome winsome) {
  }

  @Override
  public Either<String, User> signUp(String username, String password, List<String> tags)
      throws RemoteException {

    return winsome
        .register(username, password, tags);
  }

  @Override
  public void registerFollowersCallback(IRemoteClient remoteClient) throws RemoteException {
    remotes.putIfAbsent(remoteClient.getUsername(), remoteClient);
  }

  @Override
  public void unregisterFollowersCallback(IRemoteClient remoteClient) throws RemoteException {
    remotes.compute(remoteClient.getUsername(), (k, v) -> null);
  }

  public void notify(String performer, String receiver, Boolean hasFollowed) throws RemoteException {
    remotes.computeIfPresent(receiver,
        (__, rc) -> {
          try {
            if (hasFollowed)
              rc.newFollower(performer);
            else
              rc.deleteFollower(performer);
          } catch (RemoteException e) {
            e.printStackTrace();
          }
          return rc;
        });
  }

  public static RemoteServer of(Winsome winsome) {
    return new RemoteServer(winsome);
  }

}
