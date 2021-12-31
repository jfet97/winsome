package server.RMI;

import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import client.RMI.IRemoteClient;
import domain.user.User;
import io.vavr.control.Either;
import winsome.Winsome;

public class RemoteServer extends RemoteObject implements IRemoteServer {

  Winsome winsome;
  Consumer<String> onNewClientFollowersRegistration = u -> {
  };
  private ConcurrentMap<String, IRemoteClient> remotes = new ConcurrentHashMap<>();

  private RemoteServer(Winsome winsome, Consumer<String> onNewClientFollowersRegistration) {
    super();
    this.winsome = winsome;
  }

  @Override
  public Either<String, User> signUp(String username, String password, List<String> tags)
      throws RemoteException {

    return winsome
        .register(username, password, tags);
  }

  @Override
  public void registerFollowersCallback(IRemoteClient remoteClient) throws RemoteException {
    // putIfAbsent returns null if there was no previous mapping for the key
    if (remotes.putIfAbsent(remoteClient.getUsername(), remoteClient) == null) {
      onNewClientFollowersRegistration.accept(remoteClient.getUsername());
    }
  }

  @Override
  public void unregisterFollowersCallback(IRemoteClient remoteClient) throws RemoteException {
    remotes.compute(remoteClient.getUsername(), (k, v) -> null);
  }

  // not available to clients
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

  // not available to clients
  public void replaceAll(String receiver, Set<String> fs) throws RemoteException {
    remotes.computeIfPresent(receiver,
        (__, rc) -> {
          try {
            rc.replaceFollowers(fs);
          } catch (RemoteException e) {
            e.printStackTrace();
          }
          return rc;
        });
  }

  public static RemoteServer of(Winsome winsome, Consumer<String> onNewClientFollowersRegistration) {
    return new RemoteServer(winsome, onNewClientFollowersRegistration);
  }

}
