/**
 * Copyright (C) 2005-2008 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.plugin.rosterrestrictions;

import java.io.File;
import java.util.Collection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.openfire.PacketDeliverer;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.roster.Roster;
import org.jivesoftware.openfire.roster.RosterItem;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.openfire.handler.PresenceSubscribeHandler;
import org.xmpp.packet.Packet;
import org.xmpp.packet.IQ;
import org.xmpp.packet.Presence;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.JID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Roster Restrictions plugin.
 *
 * @author Gugli.
 */
public class RosterRestrictionsPlugin implements Plugin {
    
    private static final Logger Log = LoggerFactory.getLogger(RosterRestrictionsPlugin.class);
    private static final String SIZE_RESTRICTION_ENABLED  = "rosterrestrictions.size.enabled";
    private static final String SIZE_RESTRICTION_DEFAULT  = "rosterrestrictions.size.default";
    private static final String SIZE_RESTRICTION_REQUEST  = "rosterrestrictions.size.request";
    private static final String CONNECTION_USE_PROVIDER   = "rosterrestrictions.connection.useProvider";
    private static final String CONNECTION_DRIVER         = "rosterrestrictions.connection.driver";
    private static final String CONNECTION_DRIVER_STRING  = "rosterrestrictions.connection.driverstring";
    
    XMPPServer server = XMPPServer.getInstance();
    private boolean useConnectionProvider = false;     
    private String jdbcDriver = "";   
    private String connectionString = "";   
    private boolean sizeEnabled = false;
    private String sizeRequest = "";
    private int sizeDefault = 50;
    private RosterRestrictionsInterceptor interceptor = null;
    
    public RosterRestrictionsPlugin() {
        
        // Convert XML based provider setup to Database based
        JiveGlobals.migrateProperty(SIZE_RESTRICTION_ENABLED);
        JiveGlobals.migrateProperty(SIZE_RESTRICTION_DEFAULT);
        JiveGlobals.migrateProperty(SIZE_RESTRICTION_REQUEST);
        JiveGlobals.migrateProperty(CONNECTION_USE_PROVIDER);
        JiveGlobals.migrateProperty(CONNECTION_DRIVER);
        JiveGlobals.migrateProperty(CONNECTION_DRIVER_STRING);

        sizeEnabled           = JiveGlobals.getBooleanProperty(SIZE_RESTRICTION_ENABLED);
        sizeDefault           = JiveGlobals.getIntProperty(SIZE_RESTRICTION_DEFAULT, 50);
        sizeRequest           = JiveGlobals.getProperty(SIZE_RESTRICTION_REQUEST);
        useConnectionProvider = JiveGlobals.getBooleanProperty(CONNECTION_USE_PROVIDER);
        jdbcDriver            = JiveGlobals.getProperty(CONNECTION_DRIVER);
        connectionString      = JiveGlobals.getProperty(CONNECTION_DRIVER_STRING);
        
        // Load the JDBC driver and connection string.
        if (!useConnectionProvider) {
            try {
                Class.forName(jdbcDriver).newInstance();
            }
            catch (Exception e) {
                Log.error("Unable to load JDBC driver: " + jdbcDriver, e);
                return;
            }
        }
        
        interceptor = new RosterRestrictionsInterceptor(server.getPacketDeliverer(), server.getUserManager());
        InterceptorManager.getInstance().addInterceptor(interceptor);
    }

    public void initializePlugin(PluginManager manager, File pluginDirectory) {
    }

    public void destroyPlugin() {
		InterceptorManager.getInstance().removeInterceptor(interceptor);
        interceptor = null;
    }
        
    private Connection getConnection() throws SQLException {
        if (useConnectionProvider) {
            return DbConnectionManager.getConnection();
        } else {
            return DriverManager.getConnection(connectionString);
        }
    }
    
    /**
     * Intercepts Roster Add requests and stop them if roster exeeds maximum allowed size
     */
    public class RosterRestrictionsInterceptor implements PacketInterceptor {

        private PacketDeliverer deliverer;
        private UserManager userManager;
        
        public RosterRestrictionsInterceptor(PacketDeliverer deliverer, UserManager userManager) {
            this.deliverer = deliverer;
            this.userManager = userManager;
        }
        
        private boolean isItemSubscribed(RosterItem.SubType subStatus, RosterItem.AskType askStatus) {
            boolean result = 
                subStatus == RosterItem.SUB_BOTH || 
                subStatus == RosterItem.SUB_TO || 
                askStatus == RosterItem.ASK_SUBSCRIBE;
            return result;
        }
        
        private boolean isRosterFull(Roster roster) {
            String username = roster.getUsername();
            Log.info("check if Roster is full for " + username);
            if(!sizeEnabled)
                return false;
            
            int maxSize = sizeDefault;
            Connection connection = null;
            PreparedStatement pstmt = null;
            ResultSet rs = null;
            try {
                connection = getConnection();
                pstmt = connection.prepareStatement(sizeRequest);
                pstmt.setString(1, username);
                rs = pstmt.executeQuery();
                
                if (rs.next()) {
                    String maxSizeString = rs.getString(1);
                    maxSize = Integer.parseInt(maxSizeString);
                }
                Log.info("\tmaxSize for user "+username+" = "+String.valueOf(maxSize));
            } catch (Exception e) {
                Log.error("Unable to get roster maxSize for user "+username+". Using default maxSize", e);
            } finally {
                DbConnectionManager.closeConnection(rs, pstmt, connection);
            }
            
            int currentSize = 0;
            for (final RosterItem item : roster.getRosterItems()) {
                if( isItemSubscribed(item.getSubStatus(), item.getAskStatus()) )
                    currentSize++;
            }
            Log.info("\tcurrentSize for user "+username+" = "+String.valueOf(currentSize));
            return currentSize >= maxSize;
        }
    
        private boolean getIsOperationForbidden(Roster roster, RosterItem currentItem, RosterItem.SubType newItemSubStatus, RosterItem.AskType newItemAskStatus) {
            // If subcription status has changed
            Log.info("Check if Roster operation for " + roster.getUsername() + " is allowed.");
            boolean wasSubscribed = (currentItem != null) && isItemSubscribed(currentItem.getSubStatus(), currentItem.getAskStatus());
            boolean isSubscribed = isItemSubscribed(newItemSubStatus, newItemAskStatus); 
            if(currentItem != null)
                Log.info("\tcurrentItem.getSubStatus()=" + currentItem.getSubStatus() + " currentItem.getAskStatus()="+currentItem.getAskStatus());
            else
                Log.info("\tcurrentItem=null" );
            Log.info("\tnewItemSubStatus=" + newItemSubStatus + " newItemAskStatus="+newItemAskStatus);
            Log.info("\twasSubscribed=" + ((wasSubscribed)?"1":"0") + " isSubscribed="+((isSubscribed)?"1":"0"));
            return !wasSubscribed && isSubscribed && isRosterFull(roster);
        }
        
        @Override
        public void interceptPacket(Packet packet, Session session, boolean incoming, boolean processed) throws PacketRejectedException {
            if(!incoming) return; // Don't intercept outgoing messages
            if(processed) return; // Don't intercept after processing
                
            JID sender = packet.getFrom();
            if(sender.getNode() == null) return; // Don't intercept invalid users
            if(!server.isLocal(sender)) return;  // Don't intercept remote users
            if(!userManager.isRegisteredUser(sender.getNode())) return; // Don't intercept anonymous users
                
            // Fetch user's current Roster
            Roster sendersRoster = null;
            try {
                sendersRoster = userManager.getUser(sender.getNode()).getRoster();
            } catch ( UserNotFoundException e) {
                return; // User without roster ??? Don't intercept...
            }
            
            //////////////////////////////////////
            // Get the rosterItem's JID
            JID newItemJID = null;
            if (packet instanceof Presence) {
                Presence presencePacket = (Presence)packet;
                if(presencePacket.getTo() == null) return; // Don't intercept invalid presence
                newItemJID = presencePacket.getTo();
            } else if (packet instanceof org.xmpp.packet.Roster) {
                org.xmpp.packet.Roster rosterPacket = (org.xmpp.packet.Roster)packet;
                
                if( rosterPacket.getType() != IQ.Type.set) return; // Only intercept set roster requests
                if( rosterPacket.getItems().size() != 1) return; // Only intercept valid roster requests
                
                org.xmpp.packet.Roster.Item item = rosterPacket.getItems().iterator().next();
                if (item.getSubscription() == org.xmpp.packet.Roster.Subscription.remove) return; // Don't intercept remove requests
                
                newItemJID = item.getJID();
            } else {
                return; // Don't intercept other packets
            }
            if(newItemJID == null) return; // Don't intercept invalid presence or IQ
            //////////////////////////////////////
            
            // Fetch current Roster item if exists
            RosterItem currentItem = null;
            try {
                currentItem = sendersRoster.getRosterItem(newItemJID);
            } catch ( UserNotFoundException e) {
                // New user : Continue
            }
                
            //////////////////////////////////////
            // Get the rosterItem's new Status and Ask
            RosterItem.SubType newItemSubStatus = null; 
            RosterItem.AskType newItemAskStatus = null;
            if (packet instanceof Presence) {
                Presence presencePacket = (Presence)packet;
                Presence.Type type = presencePacket.getType();
                
                RosterItem.SubType currentItemSubType = (currentItem == null) ? RosterItem.SUB_NONE : currentItem.getSubStatus();
                PresenceSubscribeHandler.Change change = PresenceSubscribeHandler.getStateChange(currentItemSubType, type, true);
                
                newItemSubStatus = change.getNewSub();
                newItemAskStatus = change.getNewAsk();
                
            } else if (packet instanceof org.xmpp.packet.Roster) {
                org.xmpp.packet.Roster rosterPacket = (org.xmpp.packet.Roster)packet;
                org.xmpp.packet.Roster.Item item = rosterPacket.getItems().iterator().next();
                newItemSubStatus = RosterItem.getSubType(item);
                newItemAskStatus = RosterItem.getAskStatus(item);
            }
            //////////////////////////////////////
            
            // Check if allowed change
            boolean isOperationForbidden = getIsOperationForbidden(sendersRoster, currentItem, newItemSubStatus, newItemAskStatus );
            if(!isOperationForbidden) return;  // Valid operation : Don't intercept
            
            // Reject the operation
            Log.info("Rejecting Roster operation for " + sender.getNode());
            throw new PacketRejectedException();
        }
    }
}
