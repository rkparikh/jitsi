/*
 * SIP Communicator, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.rss;

import java.io.*;
import java.util.*;
import net.java.sip.communicator.service.gui.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;

import net.java.sip.communicator.util.*;

/**
 * Instant messaging functionalities for the RSS protocol.
 *
 * @author Jean-Albert Vescovo
 * @author Mihai Balan
 */
public class OperationSetBasicInstantMessagingRssImpl
    implements OperationSetBasicInstantMessaging,
               RegistrationStateChangeListener
{
    private static final Logger logger
        = Logger.getLogger(OperationSetBasicInstantMessagingRssImpl.class);
    /**
     * Currently registered message listeners.
     */
    private Vector messageListeners = new Vector();

    /**
     * The currently valid persistent presence operation set.
     */
    private OperationSetPersistentPresenceRssImpl opSetPersPresence = null;

    /**
     * The protocol provider that created us.
     */
    private ProtocolProviderServiceRssImpl parentProvider = null;

    /**
     * The timer used in order to refresh one or more RSS feeds
     */
    private Timer timer = null;

    /**
     * The value corresponding to the time in ms
     * of the RSS refreshing period (here 5min)
     */
    private final int PERIOD_REFRESH_RSS = 300000;

    /**
     * The value corresponding to the time in ms that we wait before the
     * initial refresh RSS when starting the application. Ideally this should
     * be less than <tt>PERIOD_REFRESH_RSS</tt> but more than a minute in order
     * to prevent from overloading the system on startup.
     */
    private final int INITIAL_RSS_LOAD_DELAY = 150000;

    /**
     * The localised message that we should show to the user before we remove
     * a dead RSS contact
     */
    private static final String MSG_CONFIRM_REMOVE_MISSING_CONTACT
        = "confirmRemoveMissingContactMessage";

    /**
     * The title of the confirmation dialog that we show to the user before we
     * remove a dead contact
     */
    private static final String TITLE_CONFIRM_REMOVE_MISSING_CONTACT
        = "confirmRemoveMissingContactTitle";

    /**
     * Creates an instance of this operation set keeping a reference to the
     * parent protocol provider and presence operation set.
     *
     * @param provider The provider instance that creates us.
     * @param opSetPersPresence the currently valid
     * <tt>OperationSetPersistentPresenceRssImpl</tt> instance.
     */
    public OperationSetBasicInstantMessagingRssImpl(
                ProtocolProviderServiceRssImpl        provider,
                OperationSetPersistentPresenceRssImpl opSetPersPresence)
    {
        this.opSetPersPresence = opSetPersPresence;
        this.parentProvider = provider;

        parentProvider.addRegistrationStateChangeListener(this);
        if(parentProvider.isRegistered())
        {
            createTimer();
        }
    }

    /**
     * Registers a MessageListener with this operation set so that it gets
     * notifications of successful message delivery, failure or reception of
     * incoming messages.
     *
     * @param listener the <tt>MessageListener</tt> to register.
     */
    public void addMessageListener(MessageListener listener)
    {
        if(!messageListeners.contains(listener))
            messageListeners.add(listener);
    }

    /**
     * Create a Message instance for sending arbitrary MIME-encoding content.
     *
     * @param content content value
     * @param contentType the MIME-type for <tt>content</tt>
     * @param contentEncoding encoding used for <tt>content</tt>
     * @param subject a <tt>String</tt> subject or <tt>null</tt> for now
     *   subject.
     * @return the newly created message.
     */
    public Message createMessage(byte[] content, String contentType,
                                 String contentEncoding, String subject)
    {
        return new MessageRssImpl(new String(content),
                                  contentType,
                                  contentEncoding,
                                  subject);
    }

    /**
     * Create a Message instance for sending a simple text messages with
     * default (text/plain) content type and encoding.
     *
     * @param messageText the string content of the message.
     * @return Message the newly created message
     */
    public Message createMessage(String messageText)
    {
        return new MessageRssImpl(messageText,
                                  HTML_MIME_TYPE,
                                  DEFAULT_MIME_ENCODING,
                                  null);
    }

    /**
     * Unregisters <tt>listener</tt> so that it won't receive any further
     * notifications upon successful message delivery, failure or reception
     * of incoming messages..
     *
     * @param listener the <tt>MessageListener</tt> to unregister.
     */
    public void removeMessageListener(MessageListener listener)
    {
        messageListeners.remove(listener);
    }

    /**
     * Updates the RSS feed associated with rssContact. If the update has been
     * requested by the user the method would fire a message received event
     * even if there are no new items.
     *
     * @param rssContact the <tt>contact</tt> to send query to.
     * @param userRequestedUpdate indicates whether the query is triggered by
     * the user or by a scheduled timer task.
     */
    private void submitRssQuery(ContactRssImpl rssContact,
                                boolean userRequestedUpdate)
    {
        Message msg;
        boolean newName = false;
        boolean newItem = false;
        boolean update = false;
        String newDisplayName = new String();
        String oldDisplayName = new String();

        RssFeedReader rssFeed = rssContact.getRssFeedReader();

        try
        {
            rssFeed.retrieveFlow();

            //if the contact was offline then switch it to online since
            //apparently we succeeded to retrieve its flow
            if(rssContact.getPresenceStatus() == RssStatusEnum.OFFLINE)
            {
                getParentProvider().getOperationSetPresence()
                    .changePresenceStatusForContact(
                        rssContact, RssStatusEnum.ONLINE);

            }
        }
        catch (FileNotFoundException ex)
        {
            //RSS flow no longer exists - ask user to remove;
            handleFileNotFoundException(rssContact, ex);

            logger.warn("RSS flow no longer exists. Error was: "
                         + ex.getMessage());
            logger.debug(ex);
            return;
        }
        catch (OperationFailedException ex)
        {
            logger.error("Failed to retrieve RSS flow. Error was: "
                         + ex.getMessage()
                         , ex);
            return;
        }


        //we recover the feed's old name
        oldDisplayName = rssContact.getDisplayName();

        //we change the contact's displayName according to the feed's title
        newDisplayName = rssFeed.getTitle();
        if (! (newDisplayName.equals(oldDisplayName)))
        {
            newName = true;
        }
        rssContact.setDisplayName(newDisplayName);

        //we create the message containing the new items retrieved
        msg = createMessage(rssFeed.feedToString(rssContact.getLastItemKey()));

        //if a newer date is available for the current feed/contact looking
        //the date of each item of the feed retrieved, we update this date
        if (rssFeed.getLastItemKey().usesDate())
        {
            if(rssFeed.getLastItemKey().getItemDate()
                .compareTo(rssContact.getLastItemKey().getItemDate()) > 0)
            {
                rssContact.setLastItemKey(
                    new RssItemKey(rssFeed.getLastItemKey().getItemDate()));
                newItem = true;
                update = true;
            }
        }
        else
        {
            if (!rssFeed.getLastItemKey().getItemUri().equalsIgnoreCase(
                rssContact.getLastItemKey().getItemUri()))
            {
                rssContact.setLastItemKey(
                    new RssItemKey(rssFeed.getLastItemKey().getItemUri()));

                newItem = true;
                update = true;
            }
        }

        //if we have a new date or a new name on this feed/contact, we fire
        //that the contact has his properties modified in order to save it
        if (newName || newItem)
            this.opSetPersPresence.fireContactPropertyChangeEvent(
                ContactPropertyChangeEvent.
                PROPERTY_DISPLAY_NAME, rssContact,
                oldDisplayName, newDisplayName);

        //if the feed has been updated or if the user made a request on a
        //specific feed/contact, we fire a new message containing the new items
        //to the user
        if(update || userRequestedUpdate)
        {
            fireMessageEvent(new MessageReceivedEvent(msg, rssContact, new Date()));
        }
    }

    /**
     * Refreshes all the registered feeds.
     */
    public void refreshAllRssFeeds()
    {
         Vector rssContactList = new Vector();
         rssContactList = opSetPersPresence.getContactListRoot().
             getRssURLList(rssContactList);
         Iterator rssContact = rssContactList.iterator();
         while(rssContact.hasNext())
         {
             ContactRssImpl contact = (ContactRssImpl)rssContact.next();
             try
             {
                 submitRssQuery(contact, false);
             }
             catch (Exception ex)
             {
                 logger.error("Failed to refresh feed for " + contact, ex);
             }
         }
    }

    /**
     * Refreshes a specific RSS feed.
     *
     * @param rssURL the <tt>contact</tt> (feed) to be refreshed.
     */
    public void refreshRssFeed( ContactRssImpl rssURL)
    {
        submitRssQuery(rssURL, true);
    }

    /**
     * Creates the timer for refreshing RSS feeds.
     */
    public void createTimer()
    {
        if (timer != null )
            return;

        logger.trace("Creating rss timer and task.");
        RssTimerRefreshFeed refresh = new RssTimerRefreshFeed(this);
        this.timer = new Timer();
        this.timer.scheduleAtFixedRate(refresh,
                                       INITIAL_RSS_LOAD_DELAY,
                                       PERIOD_REFRESH_RSS);

        logger.trace("Done.");
    }

    /**
     * Cancels the timer if the user switched to the OFFLINE status.
     */
    public void stopTimer(){
        this.timer.cancel();
    }

    /**
     * Retrieves the feeds for a new RSS feed just added as persistent contact.
     *
     * @param contact the <tt>Contact</tt> added
     */
    public void threadedContactFeedUpdate(ContactRssImpl contact)
    {
        RssThread rssThr = new RssThread(this, contact);
        rssThr.start();
    }

    /**
     * Sends the <tt>message</tt> to the destination indicated by the
     * <tt>to</tt> contact.
     *
     * @param to the <tt>Contact</tt> to send <tt>message</tt> to
     * @param message the <tt>Message</tt> to send.
     * @throws IllegalStateException if the underlying ICQ stack is not
     *   registered and initialized.
     * @throws IllegalArgumentException if <tt>to</tt> is not an instance
     *   belonging to the underlying implementation.
     */
    public void sendInstantMessage(Contact to, Message message)
        throws  IllegalStateException,
                IllegalArgumentException
    {
        if( !(to instanceof ContactRssImpl) )
           throw new IllegalArgumentException(
               "The specified contact is not a Rss contact."
               + to);

        if( to.isPersistent() &&
            to.getPresenceStatus().equals(RssStatusEnum.OFFLINE))
        {
            MessageDeliveryFailedEvent evt =
                new MessageDeliveryFailedEvent(
                    message,
                    to,
                    MessageDeliveryFailedEvent.OFFLINE_MESSAGES_NOT_SUPPORTED,
                    new Date());
            fireMessageEvent(evt);
            return;
        }

        //refresh the present rssFeed "to"
        Message msg = new MessageRssImpl("Refreshing feed...",
            DEFAULT_MIME_TYPE, DEFAULT_MIME_ENCODING, null);

        fireMessageEvent(new MessageDeliveredEvent(msg, to, new Date()));

        threadedContactFeedUpdate((ContactRssImpl)to);
    }

    /**
     * Delivers the specified event to all registered message listeners.
     * @param evt the <tt>EventObject</tt> that we'd like delivered to all
     * registered message listeners.
     */
    private void fireMessageEvent(EventObject evt)
    {
        Iterator listeners = null;
        synchronized (messageListeners)
        {
            listeners = new ArrayList(messageListeners).iterator();
        }

        while (listeners.hasNext())
        {
            MessageListener listener
                = (MessageListener) listeners.next();

            if (evt instanceof MessageDeliveredEvent)
            {
                listener.messageDelivered( (MessageDeliveredEvent) evt);
            }
            else if (evt instanceof MessageReceivedEvent)
            {
                listener.messageReceived( (MessageReceivedEvent) evt);
            }
            else if (evt instanceof MessageDeliveryFailedEvent)
            {
                listener.messageDeliveryFailed(
                    (MessageDeliveryFailedEvent) evt);
            }
        }
    }

    /**
     * Determines whether the protocol provider (or the protocol itself) supports
     * sending and receiving offline messages. Most often this method would
     * return true for protocols that support offline messages and false for
     * those that don't. It is however possible for a protocol to support these
     * messages and yet have a particular account that does not (i.e. feature
     * not enabled on the protocol server). In cases like this it is possible
     * for this method to return true even when offline messaging is not
     * supported, and then have the sendMessage method throw an
     * OperationFailedException with code - OFFLINE_MESSAGES_NOT_SUPPORTED.
     *
     * @return <tt>true</tt> if the protocol supports offline messages and
     * <tt>false</tt> otherwise.
     */
    public boolean isOfflineMessagingSupported()
    {
        return false;
    }

    /**
     * Determines whether the protocol supports the supplied content type.
     *
     * @param contentType the type we want to check
     * @return <tt>true</tt> if the protocol supports it and
     * <tt>false</tt> otherwise.
     */
    public boolean isContentTypeSupported(String contentType)
    {
        if(contentType.equals(DEFAULT_MIME_TYPE))
            return true;
        else if(contentType.equals(HTML_MIME_TYPE))
            return true;
        else
           return false;
    }

    /**
     * Returns the protocol provider that this operation set belongs to.
     *
     * @return a reference to the <tt>ProtocolProviderServiceRssImpl</tt>
     * instance that this operation set belongs to.
     */
    public ProtocolProviderServiceRssImpl getParentProvider()
    {
        return this.parentProvider;
    }

    /**
     * Returns a reference to the presence operation set instance used by our
     * source provider.
     *
     * @return a reference to the <tt>OperationSetPersistentPresenceRssImpl</tt>
     * instance used by this provider.
     */
    public OperationSetPersistentPresenceRssImpl getOpSetPersPresence()
    {
        return this.opSetPersPresence;
    }

    /**
     * The method is called by the ProtocolProvider whenever a change in the
     * registration state of the corresponding provider has occurred. We use
     * it to start and stop the timer that periodically checks for RSS updates.
     *
     * @param evt ProviderStatusChangeEvent the event describing the status
     * change.
     */
    public void registrationStateChanged(RegistrationStateChangeEvent evt)
    {
        if (evt.getNewState().equals(RegistrationState.REGISTERED))
        {
            if (timer == null)
            {
                createTimer();
            }
       }
       else if(timer != null)
       {
            timer.cancel();
            timer = null;
       }
    }

    /**
     * Queries the user as to whether or not the specified contact should be
     * removed and removes it if necessary.
     *
     * @param contact the contact that has caused the
     * <tt>FileNotFoundException</tt> and that we should probably remove.
     * @param ex the <tt>FileNotFoundException</tt>
     * that is causing all the commotion.
     */
    private void handleFileNotFoundException(ContactRssImpl contact,
                                             FileNotFoundException ex)
    {
        new FileNotFoundExceptionHandler(contact).start();
    }

    /**
     * A thread that queries the user as to whether or not the specified
     * contact should be removed and removes it if necessary.
     */
    private class FileNotFoundExceptionHandler extends Thread
    {
        private ContactRssImpl contact = null;

        /**
         * Creates a FileNotFoundExceptionHandler for the specified contact.
         *
         * @param contact the contact that has caused the
         * <tt>FileNotFoundException</tt> and that we should probably remove.
         */
        public FileNotFoundExceptionHandler(ContactRssImpl contact)
        {
            setName(FileNotFoundExceptionHandler.class.getName());

            this.contact = contact;
        }

        /**
         * Queries the user as to whether or not the specified
         * contact should be removed and removes it if necessary.
         */
        public void run()
        {
            //do not bother the user with offline contacts
            if (contact.getPresenceStatus() == RssStatusEnum.OFFLINE)
            {
                return;
            }

            UIService uiService = RssActivator.getUIService();
            String title = RssActivator.getResources()
                .getI18NString(TITLE_CONFIRM_REMOVE_MISSING_CONTACT);
            String message = RssActivator.getResources()
                .getI18NString(MSG_CONFIRM_REMOVE_MISSING_CONTACT,
                    new String[]{contact.getAddress()});

            //switch the contact to an offline state so that we don't ask
            //the user what to do about it any more.
            getParentProvider().getOperationSetPresence()
                .changePresenceStatusForContact(contact,
                                                RssStatusEnum.OFFLINE);

            int result = uiService.getPopupDialog()
                .showConfirmPopupDialog(message,
                                        title,
                                        PopupDialog.YES_NO_OPTION);

            if (result == PopupDialog.YES_OPTION)
            {
                //remove contact
                try
                {
                    getParentProvider().getOperationSetPresence()
                        .unsubscribe(contact);
                }
                catch (OperationFailedException exc)
                {
                    logger.info("We could not remove a dead contact", exc);
                }
            }
        }
    }
}
