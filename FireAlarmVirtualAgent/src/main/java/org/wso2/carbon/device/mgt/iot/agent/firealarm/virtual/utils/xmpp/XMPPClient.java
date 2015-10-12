/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.device.mgt.iot.agent.firealarm.virtual.utils.xmpp;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.AndFilter;
import org.jivesoftware.smack.filter.FromContainsFilter;
import org.jivesoftware.smack.filter.OrFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.filter.ToContainsFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.wso2.carbon.device.mgt.iot.agent.firealarm.virtual.core.AgentConstants;
import org.wso2.carbon.device.mgt.iot.agent.firealarm.virtual.exception.AgentCoreOperationException;

/**
 * This class contains the Agent specific implementation for all the XMPP functionality. This
 * includes connecting to a XMPP Server & Login using the device's XMPP-Account, Setting
 * listeners and filters on incoming XMPP messages and Sending XMPP replies for control signals
 * received. Makes use of the 'Smack-XMPP' library provided by jivesoftware/igniterealtime.
 * <p/>
 * It is an abstract class with an abstract method 'processXMPPMessage' allowing the user to have
 * their own implementation of the actions to be taken upon receiving an appropriate XMPP message.
 */
public abstract class XMPPClient {
	private static final Log log = LogFactory.getLog(XMPPClient.class);

	private int replyTimeoutInterval = 500;    // millis
	private String server;
	private int port;
	private ConnectionConfiguration config;
	private XMPPConnection connection;
	private PacketFilter filter;
	private PacketListener listener;

	/**
	 * Constructor for XMPPClient passing server-IP and the XMPP-port.
	 *
	 * @param server the IP of the XMPP server.
	 * @param port   the XMPP server's port to connect to. (default - 5222)
	 */
	public XMPPClient(String server, int port) {
		this.server = server;
		this.port = port;
		initXMPPClient();
	}

	/**
	 * Initializes the XMPP Client.
	 * Sets the time-out-limit whilst waiting for XMPP-replies from server. Creates the XMPP
	 * configurations to connect to the server and creates the XMPPConnection object used for
	 * connecting and Logging-In.
	 */
	private void initXMPPClient() {
		log.info(AgentConstants.LOG_APPENDER + String.format(
				"Initializing connection to XMPP Server at %1$s via port %2$d......", server,
				port));
		SmackConfiguration.setPacketReplyTimeout(replyTimeoutInterval);
		config = new ConnectionConfiguration(server, port);
//		TODO:: Need to enable SASL-Authentication appropriately
		config.setSASLAuthenticationEnabled(false);
		config.setSecurityMode(ConnectionConfiguration.SecurityMode.disabled);
		connection = new XMPPConnection(config);
	}

	/**
	 * Connects to the XMPP-Server and if successfully established connection, then tries to Log
	 * in using the device's XMPP Account credentials. (The XMPP-Account specific to the device is
	 * created in the XMPP server whilst downloading the Agent from the IoT Server) .
	 *
	 * @param username the username of the device's XMPP-Account.
	 * @param password the password of the device's XMPP-Account.
	 * @param resource the resource the resource, specific to the XMPP-Account to which the login
	 *                 is made to
	 * @throws AgentCoreOperationException in the event of 'Connecting to' or 'Logging into' the
	 *                                     XMPP server fails.
	 */
	public void connectAndLogin(String username, String password, String resource)
			throws AgentCoreOperationException {
		try {
			connection.connect();
			log.info(AgentConstants.LOG_APPENDER + String.format(
					"Connection to XMPP Server at %1$s established successfully......", server));

		} catch (XMPPException xmppExcepion) {
			String errorMsg =
					"Connection attempt to the XMPP Server at " + server + " via port " + port +
							" failed.";
			log.info(AgentConstants.LOG_APPENDER + errorMsg);
			throw new AgentCoreOperationException(errorMsg, xmppExcepion);
		}

		if (connection.isConnected()) {
			try {
				if (resource == null) {
					connection.login(username, password);
					log.info(AgentConstants.LOG_APPENDER + String.format(
							"Logged into XMPP Server at %1$s as user %2$s......", server,
							username));
				} else {
					connection.login(username, password, resource);
					log.info(AgentConstants.LOG_APPENDER + String.format(
							"Logged into XMPP Server at %1$s as user %2$s on resource %3$s......",
							server, username, resource));
				}
			} catch (XMPPException xmppException) {
				String errorMsg =
						"Login attempt to the XMPP Server at " + server + " with username - " +
								username + " failed.";
				log.info(AgentConstants.LOG_APPENDER + errorMsg);
				throw new AgentCoreOperationException(errorMsg, xmppException);
			}
		}
	}

	/**
	 * Sets a filter on all the incoming XMPP-Messages for the JID (XMPP-Account ID) passed in.
	 * Also creates a listener for the incoming messages and connects the listener to the
	 * XMPPConnection alongside the set filter.
	 *
	 * @param senderJID the JID (XMPP-Account ID) to which the filter is to be set.
	 */
	public void setMessageFilterAndListener(String senderJID) {
		filter = new AndFilter(new PacketTypeFilter(Message.class), new FromContainsFilter(
				senderJID));
		listener = new PacketListener() {
			@Override
			public void processPacket(Packet packet) {
				if (packet instanceof Message) {
					final Message xmppMessage = (Message) packet;
					Thread msgProcessThread = new Thread() {
						public void run() {
							processXMPPMessage(xmppMessage);
						}
					};
					msgProcessThread.start();
				}
			}
		};

		connection.addPacketListener(listener, filter);
	}

	/**
	 * Sets a filter on all the incoming XMPP-Messages for the From-JID & To-JID (XMPP-Account IDs)
	 * passed in. Also creates a listener for the incoming messages and connects the listener to
	 * the XMPPConnection alongside the set filter.
	 *
	 * @param senderJID    the From-JID (XMPP-Account ID) to which the filter is to be set.
	 * @param receiverJID  the To-JID (XMPP-Account ID) to which the filter is to be set.
	 * @param andCondition if true: then filter is set with 'AND' operator (senderJID &&
	 *                        receiverJID),
	 *                     if false: then the filter is set with 'OR' operator (senderJID |
	 *                     receiverJID)
	 */
	public void setMessageFilterAndListener(String senderJID, String receiverJID, boolean
			andCondition) {
		PacketFilter jidFilter;

		if (andCondition) {
			jidFilter = new AndFilter(new FromContainsFilter(senderJID), new ToContainsFilter(
					receiverJID));
		} else {
			jidFilter = new OrFilter(new FromContainsFilter(senderJID), new ToContainsFilter(
					receiverJID));
		}

		filter = new AndFilter(new PacketTypeFilter(Message.class), jidFilter);
		listener = new PacketListener() {
			@Override
			public void processPacket(Packet packet) {
				if (packet instanceof Message) {
					final Message xmppMessage = (Message) packet;
					Thread msgProcessThread = new Thread() {
						public void run() {
							processXMPPMessage(xmppMessage);
						}
					};
					msgProcessThread.start();
				}
			}
		};

		connection.addPacketListener(listener, filter);
	}


	/**
	 * Sends an XMPP message
	 *
	 * @param JID     the JID (XMPP Account ID) to which the message is to be sent to.
	 * @param message the XMPP-Message that is to be sent.
	 */
	public void sendXMPPMessage(String JID, String message) {
		sendXMPPMessage(JID, message, "Reply-From-Device");
		if (log.isDebugEnabled()) {
			log.debug(AgentConstants.LOG_APPENDER + "Message: " + message + " to XMPP JID [" +
					          JID + "] sent successfully");
		}
	}

	/**
	 * Overloaded method to send an XMPP message. Includes the subject to be mentioned in the
	 * message that is sent.
	 *
	 * @param JID     the JID (XMPP Account ID) to which the message is to be sent to.
	 * @param message the XMPP-Message that is to be sent.
	 * @param subject the subject that the XMPP-Message would carry.
	 */
	public void sendXMPPMessage(String JID, String message, String subject) {
		Message xmppMessage = new Message();
		xmppMessage.setTo(JID);
		xmppMessage.setSubject(subject);
		xmppMessage.setBody(message);
		xmppMessage.setType(Message.Type.chat);
		connection.sendPacket(xmppMessage);
	}

	/**
	 * Checks whether the connection to the XMPP-Server persists.
	 *
	 * @return true if the client is connected to the XMPP-Server, else false.
	 */
	public boolean isConnected() {
		return connection.isConnected();
	}

	/**
	 * Sets the client's time-out-limit whilst waiting for XMPP-replies from server.
	 *
	 * @param millis the time in millis to be set as the time-out-limit whilst waiting for a
	 *               XMPP-reply.
	 */
	public void setReplyTimeoutInterval(int millis) {
		this.replyTimeoutInterval = millis;
	}

	/**
	 * Disables default debugger provided by the XMPPConnection.
	 */
	public void disableDebugger() {
		connection.DEBUG_ENABLED = false;
	}

	/**
	 * Closes the connection to the XMPP Server.
	 */
	public void closeConnection() {
		if (connection != null && connection.isConnected()) {
			connection.disconnect();
		}
	}

	/**
	 * This is an abstract method used for post processing the received XMPP-message. This
	 * method will be implemented as per requirement at the time of creating an object of this
	 * class.
	 *
	 * @param xmppMessage the xmpp message received by the listener.
	 */
	protected abstract void processXMPPMessage(Message xmppMessage);

}


