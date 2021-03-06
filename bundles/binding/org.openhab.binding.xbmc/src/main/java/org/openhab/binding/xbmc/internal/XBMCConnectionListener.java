package org.openhab.binding.xbmc.internal;

import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.openhab.binding.xbmc.XBMCBindingCommands;
import org.openhab.binding.xbmc.XBMCBindingProvider;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbmc.android.jsonrpc.api.AbstractCall;
import org.xbmc.android.jsonrpc.api.call.Application.GetProperties;
import org.xbmc.android.jsonrpc.api.call.VideoLibrary.GetEpisodeDetails;
import org.xbmc.android.jsonrpc.api.call.VideoLibrary.GetMovieDetails;
import org.xbmc.android.jsonrpc.api.model.ApplicationModel.PropertyValue;
import org.xbmc.android.jsonrpc.api.model.ApplicationModel.PropertyValue.Version;
import org.xbmc.android.jsonrpc.api.model.VideoModel;
import org.xbmc.android.jsonrpc.api.model.VideoModel.EpisodeDetail;
import org.xbmc.android.jsonrpc.api.model.VideoModel.MovieDetail;
import org.xbmc.android.jsonrpc.io.ApiCallback;
import org.xbmc.android.jsonrpc.io.ConnectionListener;
import org.xbmc.android.jsonrpc.io.JavaConnectionManager;
import org.xbmc.android.jsonrpc.notification.AbstractEvent;
import org.xbmc.android.jsonrpc.notification.PlayerEvent;
import org.xbmc.android.jsonrpc.notification.PlayerEvent.Item;
import org.xbmc.android.jsonrpc.notification.PlayerEvent.Play;
import org.xbmc.android.jsonrpc.notification.SystemEvent;

/**
 * This class is responsible for listening for events on a XBMC instance. Events
 * can be events send by XBMC or connect/disconnect events. Additionally this
 * class can be used to query the properties like volume, mute status etc. of a
 * XBMC instance.
 * 
 * @author Till Klocke
 * @since 1.4.0
 */
public class XBMCConnectionListener implements ConnectionListener {

	private Logger logger = LoggerFactory.getLogger(XBMCConnectionListener.class);

	private EventPublisher eventPublisher;
	private XBMCBindingProvider bindingProvider;

	private String deviceId;

	private JavaConnectionManager conManager;

	// Properties queried from the instance
	private Integer currentVolume;
	private Boolean isMute;
	private String name;
	private Version version;

	public XBMCConnectionListener(String deviceId, EventPublisher publisher, XBMCBindingProvider provider,
			JavaConnectionManager conManager) {
		this.deviceId = deviceId;
		this.eventPublisher = publisher;
		this.bindingProvider = provider;
		this.conManager = conManager;
	}

	public JavaConnectionManager getConnectionManager() {
		return conManager;
	}

	/**
	 * Sends a GetProperty request to XBMC and then updates all items which are
	 * relevant for the received values.
	 */
	public void updateProperties() {
		GetProperties call = new GetProperties("volume", "muted", "name", "version");
		conManager.call(call, new ApiCallback<PropertyValue>() {

			@Override
			public void onError(int arg0, String arg1, String arg2) {
				// TODO Auto-generated method stub

			}

			@Override
			public void onResponse(AbstractCall<PropertyValue> result) {
				logger.debug("Received updated properties for " + deviceId);
				currentVolume = result.getResult().volume;
				isMute = result.getResult().muted;
				name = result.getResult().name;
				version = result.getResult().version;

				updateItemsRepresentingProperties();

			}
		});
	}

	/**
	 * Return the device id, which is used in the configuration file
	 * 
	 * @return the configured device id
	 */
	public String getDeviceId() {
		return deviceId;
	}

	/**
	 * Issues updates for all items representing properties of the XBMC instance
	 */
	private void updateItemsRepresentingProperties() {
		updateItemsForCommand(XBMCBindingCommands.VOLUME, currentVolume);
		updateItemsForCommand(XBMCBindingCommands.NAME, name);
		updateVersion();
		if (isMute != null && isMute == Boolean.TRUE) {
			updateItemsForCommand(XBMCBindingCommands.MUTE);
		} else {
			updateItemsForCommand(XBMCBindingCommands.UNMUTE);
		}
	}

	private void updateVersion() {
		String versionString = version.major + "." + version.minor + "." + version.revision;
		updateItemsForCommand(XBMCBindingCommands.VERSION, versionString);
	}

	@Override
	public void connected() {
		logger.debug(deviceId + ": Connected");
	}

	/**
	 * When we are disconnected (due to an exception or when XBMC shuts down),
	 * we try to reconnect after 5 seconds. We do this until we succeed
	 * connecting or are shut down.
	 */
	@Override
	public void disconnected(Exception error) {
		logger.debug(deviceId + ": Disconnected, Trying to reconnect");
		if (error != null) {
			logger.debug(deviceId + ": Disconnected due to to Exception", error);
		}
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			logger.debug("Exception while waiting for reconnect", e);
		}
		conManager.reconnect();
	}

	/**
	 * Notifications send from XBMC are handled here.
	 */
	@Override
	public void notificationReceived(AbstractEvent event) {
		String methodName = event.getMethod();
		logger.debug("Received event: " + methodName);
		if (event instanceof SystemEvent.Quit) {
			// We should at least try to connect gracefully when XBMC is shut
			// down.
			conManager.disconnect();
		}
		// Special case, we want to be able to distinguish between shows
		// and movies, so we signal two more events.
		if (event instanceof PlayerEvent.Play) {
			PlayerEvent.Play playEvent = (Play) event;
			if (playEvent.data.item.type == PlayerEvent.Item.Type.EPISODE) {
				updateItemsForCommand(XBMCBindingCommands.PLAYING_TVSHOW);
			} else if (playEvent.data.item.type == PlayerEvent.Item.Type.MOVIE) {
				updateItemsForCommand(XBMCBindingCommands.PLAYING_MOVIE);
			}
			updateCurrentPlaying(playEvent);
		}
		// If XBMC has stopped playing we don't need to display the played title
		// any longer
		if (event instanceof PlayerEvent.Stop) {
			updateItemsForCommand(XBMCBindingCommands.PLAYING_TITLE, "");
		}

		XBMCBindingCommands bindingCommand = XBMCBindingCommands.getBindingCommandByMethodName(methodName);
		if (bindingCommand != null) {
			updateItemsForCommand(bindingCommand);
		} else {
			logger.debug("Received unknown event " + methodName);
		}
	}

	/**
	 * Details about the currently played title are not available in the
	 * PlayerEvent so we need to query the database for them
	 * 
	 * @param playEvent
	 */
	private void updateCurrentPlaying(PlayerEvent.Play playEvent) {
		Item item = playEvent.data.item;
		int id = item.id;
		switch (item.type) {
		case Item.Type.EPISODE:
			updateEpisodeDetails(id);
			break;
		case Item.Type.MOVIE:
			updateMovieDetails(id);
			break;
		case Item.Type.MUSICVIDEO:
			// TODO
			break;
		case Item.Type.SONG:
			// TODO
			break;
		case Item.Type.UNKNOWN:
			updatePlayingTitle("Unknown type");
			break;
		default:
			// TODO
			break;
		}

	}

	private void updateMovieDetails(int id) {
		GetMovieDetails call = new GetMovieDetails(id, "title", "year");
		conManager.call(call, new ApiCallback<VideoModel.MovieDetail>() {

			@Override
			public void onError(int arg0, String arg1, String arg2) {
				// TODO Auto-generated method stub

			}

			@Override
			public void onResponse(AbstractCall<MovieDetail> call) {
				MovieDetail detail = call.getResult();
				String title = detail.title + " (" + detail.year + ")";
				updatePlayingTitle(title);
			}
		});
	}

	private void updateEpisodeDetails(int id) {
		GetEpisodeDetails call = new GetEpisodeDetails(id, "title", "season", "episode", "showtitle");
		conManager.call(call, new ApiCallback<VideoModel.EpisodeDetail>() {

			@Override
			public void onError(int arg0, String arg1, String arg2) {
				// TODO Auto-generated method stub

			}

			@Override
			public void onResponse(AbstractCall<EpisodeDetail> call) {
				EpisodeDetail detail = call.getResult();
				String title = detail.showtitle + " (S" + detail.season + "-E" + detail.episode + ") - " + detail.title;
				updatePlayingTitle(title);
			}
		});
	}

	private void updatePlayingTitle(String title) {
		if (!StringUtils.isEmpty(title)) {
			updateItemsForCommand(XBMCBindingCommands.PLAYING_TITLE, title);
		}
	}

	private void updateItemsForCommand(XBMCBindingCommands bindingCommand) {
		updateItemsForCommand(bindingCommand, null);
	}

	private void updateItemsForCommand(XBMCBindingCommands bindingCommand, Object argument) {
		logger.debug("Searching item for binding command " + bindingCommand.toString());
		List<XBMCBindingConfig> configs = bindingProvider.findBindingConfigs(deviceId, bindingCommand);
		if (configs.isEmpty()) {
			logger.debug("Can't find any items for binding command " + bindingCommand.toString());
		}
		for (XBMCBindingConfig config : configs) {
			State state = getState(config, bindingCommand, argument);
			if (state != null) {
				if (eventPublisher != null) {
					logger.debug(deviceId + ": Posting update for item " + config.getItem().getName() + ": "
							+ state.format("%s"));
					eventPublisher.postUpdate(config.getItem().getName(), state);
				} else {
					logger.error("EventPublisher was NULL during creation of this listener...");
				}
			} else {
				logger.debug("Can't find valid state for item " + config.getItem().getName() + " for binding command "
						+ bindingCommand.toString());
			}
		}
	}

	private State getState(XBMCBindingConfig config, XBMCBindingCommands bindingCommand, Object argument) {
		if (argument != null) {
			if (argument instanceof Integer) {
				return new DecimalType(((Integer) argument).longValue());
			} else if (argument instanceof String) {
				return new StringType(argument.toString());
			} else if (argument instanceof Boolean) {
				Boolean bool = (Boolean) argument;
				return bool.booleanValue() ? OnOffType.ON : OnOffType.OFF;
			}
		}
		return config.getStateForEvent(bindingCommand);
	}

}
