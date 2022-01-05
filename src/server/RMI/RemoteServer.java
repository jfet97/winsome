package server.RMI;

import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;

import client.RMI.IRemoteClient;
import io.vavr.control.Either;
import winsome.Winsome;

public class RemoteServer extends RemoteObject implements IRemoteServer {

  Winsome winsome;
  // callback called when a new client registers itself to the followers
  // notifications
  BiConsumer<String, IRemoteClient> onNewClientFollowersRegistration = (u, rc) -> {
  };
  // contains all the remotes we have to notify
  private ConcurrentMap<String, IRemoteClient> remotes = new ConcurrentHashMap<>();

  private RemoteServer(Winsome winsome, BiConsumer<String, IRemoteClient> onNewClientFollowersRegistration) {
    super();
    this.winsome = winsome;
    this.onNewClientFollowersRegistration = onNewClientFollowersRegistration;
  }

  // signup handler
  @Override
  public Either<String, String> signUp(String username, String password, List<String> tags)
      throws RemoteException {

    // try to register the new user
    return winsome
        .register(username, password, tags).map(u -> u.username);
  }

  @Override
  public void registerFollowersCallback(IRemoteClient remoteClient) throws RemoteException {
    // putIfAbsent returns null if there was no previous mapping for the key
    remotes.compute(remoteClient.getUsername(), (k, v) -> {
      // call the callback before inserting the remote client into the map
      // (synchronization with the following methods)
      onNewClientFollowersRegistration.accept(k, remoteClient);
      return remoteClient;
    });

  }

  @Override
  public void unregisterFollowersCallback(IRemoteClient remoteClient) throws RemoteException {
    remotes.compute(remoteClient.getUsername(), (k, v) -> null);
  }

  // not available to clients
  public void notify(String performer, List<String> tags, String receiver, Boolean hasFollowed) throws RemoteException {
    remotes.computeIfPresent(receiver,
        (__, rc) -> {
          try {
            if (hasFollowed)
              rc.newFollower(performer, tags);
            else
              rc.deleteFollower(performer);
          } catch (RemoteException e) {
            e.printStackTrace();
            // the client has disconnected without calling the
            // unregisterFollowersCallback method
            return null;
          }
          return rc;
        });
  }

  // not available to clients
  public void replaceAll(String receiver, Map<String, List<String>> fs) throws RemoteException {
    remotes.computeIfPresent(receiver,
        (__, rc) -> {
          try {
            rc.replaceFollowers(fs);
          } catch (RemoteException e) {
            e.printStackTrace();
            // the client has disconnected without calling the
            // unregisterFollowersCallback method
            return null;
          }
          return rc;
        });
  }

  public static RemoteServer of(Winsome winsome, BiConsumer<String, IRemoteClient> onNewClientFollowersRegistration) {
    return new RemoteServer(winsome, onNewClientFollowersRegistration);
  }

}
