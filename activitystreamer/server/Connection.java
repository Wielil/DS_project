package activitystreamer.server;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

//wei
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import activitystreamer.util.Settings;

public class Connection extends Thread {
	private static final Logger log = LogManager.getLogger();
	private DataInputStream in;
	private DataOutputStream out;
	private BufferedReader inreader;
	private PrintWriter outwriter;
	private boolean open = false;
	private Socket socket;
	private boolean term = false;
	private boolean serverFlag = false; // Indicate a connection is a valid server (default: false)
	private boolean clientFlag = false; // Indicate a connection is a valid client (default: false)
	private boolean closedFlag = false; // Indicate a connection is closed

	Connection(Socket socket) throws IOException {
		in = new DataInputStream(socket.getInputStream());
		out = new DataOutputStream(socket.getOutputStream());
		inreader = new BufferedReader(new InputStreamReader(in));
		outwriter = new PrintWriter(out, true);
		this.socket = socket;
		open = true;
		start();
	}

	/*
	 * returns true if the message was written, otherwise false
	 */
	public boolean writeMsg(String msg) {
		if (open) {
			outwriter.println(msg);
			outwriter.flush();
			return true;
		}
		return false;
	}

	public void closeCon() {
		if (open) {
			log.info("closing connection " + Settings.socketAddress(socket));
			try {
				term = true;
				inreader.close();
				out.close();
			} catch (IOException e) {
				// already closed?
				log.error("received exception closing the connection " + Settings.socketAddress(socket) + ": " + e);
			}
		}
	}

	public void run() {
		try {
			String data;

			// wei
			// send an Authenticate command if connections list only has one
			// connection and a remote host port is supplied
			// authenticate the initial connection.
			// if(Control.getInstance().getConnections().size() == 1 &&
			// Settings.getRemoteHostname()!=null) {
			// this.sendAuthenticate();
			// }

			// Shaoxi
			// Ensure the processing step run after the Control instance was created
			Thread.sleep(100);
			log.debug("Processing data...");
			while (!term && (data = inreader.readLine()) != null) {
				// wei
				log.info("Processing data: " + data);
				term = Control.getInstance().process(this, data);
			}

			log.debug("connection closed to " + Settings.socketAddress(socket));

			// remove the connection from connections list after broadcasting
			if (isClient()) {
				Control.getInstance().connectionClosed(this);
			}
			
			if (isServer()) {
				setClosed();
				setServer(false);
				
				// Stop all register / lock tasks
				Control.getInstance().stopRegLock();
				
				// if this connection is at index 0 of connection list
				// establish an initialConnection to backup server
				// else, close it without removing it from this list
				if (Control.getInstance().getConnections().indexOf(this) == 0) {
					Control.getInstance().initiateConnection();
				}
			}
			
			in.close();

		} catch (IOException | InterruptedException e) {
			log.error("connection " + Settings.socketAddress(socket) + " closed with exception: " + e);
			Control.getInstance().connectionClosed(this);
		}
		open = false;
	}

	public Socket getSocket() {
		return socket;
	}

	public boolean isOpen() {
		return open;
	}

	public boolean isTerm() {
		return term;
	}

	// Shaoxi
	// Used to set the connection as a server in Control.java
	void setServer(boolean flag) {
		serverFlag = flag;
	}

	// return if it is a valid server
	boolean isServer() {
		return serverFlag;
	}

	// Shaoxi
	// Used to set the connection as a client in Control.java
	void setClient() {
		Settings.incLoad();
		clientFlag = true;
	}

	// return if it is a valid client
	boolean isClient() {
		return clientFlag;
	}

	// Wei
	// Used to set the connection to be closed
	void setClosed() {
		closedFlag = true;
	}

	// return if it is closed
	boolean isClosed() {
		return closedFlag;
	}

	// Get the full address of the connection
	// return: "hostname:port"
	public String getFullAddr() {
		return getSocket().getInetAddress() + ":" + getSocket().getPort();
	}

	public String getRemoteHost() {
		return getSocket().getInetAddress().toString();
	}

	public int getRemotePort() {
		return getSocket().getPort();
	}
}
