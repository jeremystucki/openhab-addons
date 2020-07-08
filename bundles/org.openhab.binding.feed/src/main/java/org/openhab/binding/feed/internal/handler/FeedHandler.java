/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.feed.internal.handler;

import static org.openhab.binding.feed.internal.FeedBindingConstants.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.eclipse.smarthome.core.library.types.DateTimeType;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.*;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.builder.ChannelBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;

/**
 * The {@link FeedHandler } is responsible for handling commands, which are
 * sent to one of the channels and for the regular updates of the feed data.
 *
 * @author Svilen Valkanov - Initial contribution
 */
public class FeedHandler extends BaseThingHandler {

    private final ChannelGroupUID titleChannelGroupUID = new ChannelGroupUID(getThing().getUID(), "Titles");
    private final ChannelGroupUID descriptionChannelGroupUID = new ChannelGroupUID(getThing().getUID(), "Descriptions");
    private final ChannelGroupUID dateChannelGroupUID = new ChannelGroupUID(getThing().getUID(), "Dates");

    private Logger logger = LoggerFactory.getLogger(FeedHandler.class);

    private ScheduledFuture<?> refreshTask;
    private SyndFeed currentFeedState;
    private long lastRefreshTime;

    private FeedHandlerConfiguration configuration;

    public FeedHandler(Thing thing) {
        super(thing);
        currentFeedState = null;
    }

    @Override
    public void initialize() {
        configuration = getConfigAs(FeedHandlerConfiguration.class);
        createDynamicChannels(configuration.numberOfEntries);

        updateStatus(ThingStatus.UNKNOWN);
        startAutomaticRefresh();
    }

    @Override
    public void handleConfigurationUpdate(@NotNull Map<String, Object> configurationParameters) {
        super.handleConfigurationUpdate(configurationParameters);

        configuration = getConfigAs(FeedHandlerConfiguration.class);
        createDynamicChannels(configuration.numberOfEntries);
    }

    private void startAutomaticRefresh() {
        int refreshTime = configuration.refresh;

        refreshTask = scheduler.scheduleWithFixedDelay(this::refreshFeedState, 0, refreshTime,
                TimeUnit.MINUTES);
        logger.debug("Start automatic refresh at {} minutes", refreshTime);
    }

    private void refreshFeedState() {
        SyndFeed feed = fetchFeedData(configuration.URL);
        boolean feedUpdated = updateFeedIfChanged(feed);

        if (feedUpdated) {
            publishDynamicChannelsIfLinked();

            List<Channel> channels = getThing().getChannels();
            for (Channel channel : channels) {
                publishChannelIfLinked(channel.getUID());
            }
        }
    }

    private void publishDynamicChannelsIfLinked() {
        int numberOfEntries = configuration.numberOfEntries;

        List<SyndEntry> entries = currentFeedState.getEntries().stream().limit(numberOfEntries)
                .collect(Collectors.toList());

        setUnusedDynamicChannelsToUndef(entries.size());

        for (int i = 0; i < entries.size(); i++) {
            SyndEntry entry = entries.get(i);

            ChannelUID titleChannelUID = new ChannelUID(titleChannelGroupUID, String.valueOf(i));
            if (isLinked(titleChannelUID)) {
                setChannelToState(titleChannelUID, getTitleState(entry));
            }

            ChannelUID descriptionChannelUID = new ChannelUID(descriptionChannelGroupUID, String.valueOf(i));
            if (isLinked(descriptionChannelUID)) {
                setChannelToState(descriptionChannelUID, getDescriptionState(entry));
            }

            ChannelUID dateChannelUID = new ChannelUID(dateChannelGroupUID, String.valueOf(i));
            if (isLinked(dateChannelUID)) {
                setChannelToState(dateChannelUID, getDateState(entry));
            }
        }
    }

    private void setUnusedDynamicChannelsToUndef(int amountOfUsedChannels) {
        getThing().getChannelsOfGroup(titleChannelGroupUID.getId()).stream().skip(amountOfUsedChannels)
                .forEach(channel -> updateState(channel.getUID(), UnDefType.UNDEF));

        getThing().getChannelsOfGroup(descriptionChannelGroupUID.getId()).stream().skip(amountOfUsedChannels)
                .forEach(channel -> updateState(channel.getUID(), UnDefType.UNDEF));

        getThing().getChannelsOfGroup(dateChannelGroupUID.getId()).stream().skip(amountOfUsedChannels)
                .forEach(channel -> updateState(channel.getUID(), UnDefType.UNDEF));
    }

    private void createDynamicChannels(int numberOfChannels) {
        // All groups have the same number of channels, so it doesn't matter which one we pick
        int existingChannels = getThing().getChannelsOfGroup(titleChannelGroupUID.getId()).size();

        ThingBuilder thingBuilder = editThing();

        for (int i = existingChannels; i < numberOfChannels; i++) {
            Channel titleChannel = ChannelBuilder
                    .create(new ChannelUID(titleChannelGroupUID, String.valueOf(i)), "String")
                    .withLabel("Title " + (i + 1)).build();

            Channel descriptionChannel = ChannelBuilder
                    .create(new ChannelUID(descriptionChannelGroupUID, String.valueOf(i)), "String")
                    .withLabel("Description " + (i + 1)).build();

            Channel dateChannel = ChannelBuilder
                    .create(new ChannelUID(dateChannelGroupUID, String.valueOf(i)), "DateTime")
                    .withLabel("Date " + (i + 1)).build();

            thingBuilder.withChannel(titleChannel);
            thingBuilder.withChannel(descriptionChannel);
            thingBuilder.withChannel(dateChannel);
        }

        updateThing(thingBuilder.build());
    }

    private void publishChannelIfLinked(ChannelUID channelUID) {
        String channelID = channelUID.getId();

        if (currentFeedState == null) {
            // This will happen if the binding could not download data from the server
            logger.trace("Cannot update channel with ID {}; no data has been downloaded from the server!", channelID);
            return;
        }

        if (!isLinked(channelUID)) {
            logger.trace("Cannot update channel with ID {}; not linked!", channelID);
            return;
        }

        State state = null;
        SyndEntry latestEntry = getLatestEntry(currentFeedState);

        switch (channelID) {
            case CHANNEL_LATEST_TITLE:
                state = getTitleState(latestEntry);
                break;
            case CHANNEL_LATEST_DESCRIPTION:
                state = getDescriptionState(latestEntry);
                break;
            case CHANNEL_LATEST_PUBLISHED_DATE:
            case CHANNEL_LAST_UPDATE:
                state = getDateState(latestEntry);
                break;
            case CHANNEL_AUTHOR:
                String author = currentFeedState.getAuthor();
                state = new StringType(getValueSafely(author));
                break;
            case CHANNEL_DESCRIPTION:
                String channelDescription = currentFeedState.getDescription();
                state = new StringType(getValueSafely(channelDescription));
                break;
            case CHANNEL_TITLE:
                String channelTitle = currentFeedState.getTitle();
                state = new StringType(getValueSafely(channelTitle));
                break;
            case CHANNEL_NUMBER_OF_ENTRIES:
                int numberOfEntries = currentFeedState.getEntries().size();
                state = new DecimalType(numberOfEntries);
                break;
            default:
                logger.debug("Unrecognized channel: {}", channelID);
        }

        setChannelToState(channelUID, state);
    }

    private void setChannelToState(ChannelUID channel, State state) {
        if (state != null) {
            updateState(channel.getId(), state);
        } else {
            logger.debug("Cannot update channel with ID {}; state not defined!", channel.getId());
        }
    }

    private State getTitleState(SyndEntry entry) {
        if (entry == null || entry.getTitle() == null) {
            return UnDefType.UNDEF;
        } else {
            String title = entry.getTitle();
            return new StringType(getValueSafely(title));
        }
    }

    private State getDescriptionState(SyndEntry entry) {
        if (entry == null || entry.getDescription() == null) {
            return UnDefType.UNDEF;
        } else {
            String description = entry.getDescription().getValue();
            return new StringType(getValueSafely(description));
        }
    }

    private State getDateState(SyndEntry entry) {
        if (entry == null || entry.getPublishedDate() == null) {
            logger.debug("Cannot update date channel. No date found in feed.");
            return null;
        } else {
            Date date = entry.getPublishedDate();
            ZonedDateTime zdt = ZonedDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
            return new DateTimeType(zdt);
        }
    }

    /**
     * This method updates the {@link #currentFeedState}, only if there are changes on the server, since the last check.
     * It compares the content on the server with the local
     * stored {@link #currentFeedState} in the {@link FeedHandler}.
     *
     * @return <code>true</code> if new content is available on the server since the last update or <code>false</code>
     *         otherwise
     */
    private synchronized boolean updateFeedIfChanged(SyndFeed newFeedState) {
        // SyndFeed class has implementation of equals ()
        if (newFeedState != null && !newFeedState.equals(currentFeedState)) {
            currentFeedState = newFeedState;
            logger.debug("New content available!");
            return true;
        }
        logger.debug("Feed content has not changed!");
        return false;
    }

    /**
     * This method tries to make connection with the server and fetch data from the feed.
     * The status of the feed thing is set to {@link ThingStatus#ONLINE}, if the fetching was successful.
     * Otherwise the status will be set to {@link ThingStatus#OFFLINE} with
     * {@link ThingStatusDetail#CONFIGURATION_ERROR} or
     * {@link ThingStatusDetail#COMMUNICATION_ERROR} and adequate message.
     *
     * @param urlString URL of the Feed
     * @return {@link SyndFeed} instance with the feed data, if the connection attempt was successful and
     *         <code>null</code> otherwise
     */
    private SyndFeed fetchFeedData(String urlString) {
        SyndFeed feed = null;
        try {
            URL url = new URL(urlString);

            URLConnection connection = url.openConnection();
            connection.setRequestProperty("Accept-Encoding", "gzip");

            BufferedReader in = null;
            if ("gzip".equals(connection.getContentEncoding())) {
                in = new BufferedReader(new InputStreamReader(new GZIPInputStream(connection.getInputStream())));
            } else {
                in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            }

            SyndFeedInput input = new SyndFeedInput();
            feed = input.build(in);
            in.close();

            if (this.thing.getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
            }
        } catch (MalformedURLException e) {
            logger.warn("Url '{}' is not valid: ", urlString, e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, e.getMessage());
            return null;
        } catch (IOException e) {
            logger.warn("Error accessing feed: {}", urlString, e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
            return null;
        } catch (IllegalArgumentException e) {
            logger.warn("Feed URL is null ", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, e.getMessage());
            return null;
        } catch (FeedException e) {
            logger.warn("Feed content is not valid: {} ", urlString, e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, e.getMessage());
            return null;
        }

        return feed;
    }

    /**
     * Returns the most recent entry or null, if no entries are found.
     */
    private SyndEntry getLatestEntry(SyndFeed feed) {
        List<SyndEntry> allEntries = feed.getEntries();
        SyndEntry lastEntry = null;
        if (!allEntries.isEmpty()) {
            /*
             * The entries are stored in the SyndFeed object in the following order -
             * the newest entry has index 0. The order is determined from the time the entry was posted, not the
             * published time of the entry.
             */
            lastEntry = allEntries.get(0);
        } else {
            logger.debug("No entries found");
        }
        return lastEntry;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            // safeguard for multiple REFRESH commands for different channels in a row
            if (isMinimumRefreshTimeExceeded()) {
                SyndFeed feed = fetchFeedData(configuration.URL);
                updateFeedIfChanged(feed);
            }
            publishChannelIfLinked(channelUID);
            publishDynamicChannelsIfLinked();
        } else {
            logger.debug("Command {} is not supported for channel: {}. Supported command: REFRESH", command,
                    channelUID.getId());
        }
    }

    @Override
    public void dispose() {
        if (refreshTask != null) {
            refreshTask.cancel(true);
        }
        lastRefreshTime = 0;
    }

    private boolean isMinimumRefreshTimeExceeded() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastRefresh = currentTime - lastRefreshTime;
        if (timeSinceLastRefresh < MINIMUM_REFRESH_TIME) {
            return false;
        }
        lastRefreshTime = currentTime;
        return true;
    }

    public String getValueSafely(String value) {
        return value == null ? new String() : value;
    }
}
