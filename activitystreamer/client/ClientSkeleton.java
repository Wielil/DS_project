package activitystreamer.client;


import java.io.IOException;
import java.net.Socket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import activitystreamer.CConnection;
import activitystreamer.util.Settings;
import java.net.UnknownHostException;

// wei
//import java.util.Scanner;
//import java.util.logging.Level;


public class ClientSkeleton extends Thread {
    private static final Logger log = LogManager.getLogger();
    private Socket socket;
    private static ClientSkeleton clientSolution;
    private TextFrame textFrame;
     //yuri
    private static CConnection clientThread ;
    private String remoteHost = Settings.getRemoteHostname();
    private int remotePort = Settings.getRemotePort();
    private boolean loginFlag=false;
   // private boolean term = false;
    //private boolean open = false;
    
  
    	
    public static ClientSkeleton getInstance(){
        if(clientSolution==null){
            clientSolution = new ClientSkeleton();
        }
        return clientSolution;
    }
	
    public ClientSkeleton(){
        //Yuri 
        	textFrame = new TextFrame();
        	//Start a new thread
        	newThread(remoteHost, remotePort);
        	//According to the input content to determine which method to call
        	if(Settings.getUsername() =="anonymous"&&Settings.getSecret()==null) {
            	   sendLogin();
        	 }
        	else 
        		 sendRegistration();
        	 start(); 

    }
    
    public void newThread(String remoteHost, int remotePort) {
		
			try {
				  socket = new Socket(remoteHost, remotePort);
				  clientThread = new CConnection (socket);
			} catch (UnknownHostException e) {
	            e.printStackTrace();
	        } catch (IOException e) {
	            e.printStackTrace();
	        }
    }
    
    
   
    
    // yuri
    // a function to send "login" command to server.
    public void sendLogin() {
        // create an authenticate json obj
        JSONObject Login = new JSONObject();
        Login.put("command", "LOGIN");
        if(Settings.getUsername() =="anonymous"&&Settings.getSecret()==null) {
      	   Login.put("username", Settings.getUsername());
         }
        else{
           	Login.put("username", Settings.getUsername());
           	Login.put("secret", Settings.getSecret());
        }
        
        clientThread.writeMsg(Login.toJSONString());
        log.info("LOGIN REQUEST SENT");
        
    }
    //yuri
    //a function to send "registration" command to server
    public void sendRegistration() {
    	     // create an authenticate json objecj
    	      JSONObject Regist = new JSONObject ();
    	      Regist.put("command","REGISTER");
    	      if(Settings.getUsername()!=null&&Settings.getSecret()==null){
    	           	Regist.put("username", Settings.getUsername());
    	           	Settings.setSecret("123456");
    	           	//log.info("Set initial secret to 123. ");
    	           	Regist.put("secret", Settings.getSecret());
    	        }
    	      else {
    	    	      Regist.put("username", Settings.getUsername());
    	    	      Regist.put("secret", Settings.getSecret());
    	      }
    	      clientThread.writeMsg(Regist.toJSONString());
    	      log.info("REGIST REQUEST SENT");
    	      
    }
    
    
    @SuppressWarnings("unchecked")
    public void sendActivityObject(JSONObject activityObj) {
        // yuri
        JSONObject clientMeg = new JSONObject();
        clientMeg.put("command", "ACTIVITY_MESSAGE");
        clientMeg.put("username", Settings.getUsername());
        clientMeg.put("secret", Settings.getSecret());
        clientMeg.put("activity", activityObj);
       
        clientThread.writeMsg(clientMeg.toJSONString());
        log.info("CLIENT MESSAGE SENT"); 
        
    }
    
    public void disconnect(){
   	 if (loginFlag) {
   		 JSONObject disconnect =new JSONObject();
   		 disconnect.put("command", "LOGOUT");
   		 clientThread.writeMsg(disconnect.toJSONString());
   	 }
   	     clientThread.disconnect();
       }
    
	
  

   
    /*
     * Processing incoming messages from the connection.
     * Return true if the connection should close.
     */
    public synchronized boolean process(CConnection thread,String msg) {
        //yuri
        JSONParser parser = new JSONParser();
        try {
            //yuri
        	    // parese msg to JSONObject
            JSONObject JSONmsg = (JSONObject) parser.parse(msg);
            textFrame.setOutputText(JSONmsg);
            log.info("Received Message From Server:"+msg);
            // get command
            String command = (String) JSONmsg.get("command");
            switch(command)
            {   //yuri
                case "AUTHENTICATION_FAIL":
                    log.info((String) JSONmsg.get("info"));
                    loginFlag =false;
                    return loginFlag;
                    //return false;
                //yuri    
                case "LOGIN_FAIL":
                    log.info((String) JSONmsg.get("info"));
                    loginFlag=false;
                    return loginFlag;
                    //return true;
                    
                case "ACTIVITY_BROADCAST":
                    log.info((String) JSONmsg.get("activity"));
                    return false;
                    
                case "REDIRET":
                    remoteHost = (String) JSONmsg.get("hostname");
                    remotePort = Integer.parseInt((String) JSONmsg.get("port"));
                    log.info("THIS SERVER HANDLE TOO MUCH LOAD, REDIRECT TO SERVER %s:%d",
                            remoteHost, remotePort);
                    // starts the protocol afresh.
                    clientSolution = new ClientSkeleton();
                    ClientSkeleton newc = ClientSkeleton.getInstance();
                    return true;
                //yuri
                case "REGISTER_FAILED":
					newThread(remoteHost, remotePort);
					sendLogin();
					thread.setTerm(true);//setup a new thread
					break;
				//yuri
				case "REGISTER_SUCCESS":
					sendLogin();
					break;
				//yuri
				case "LOGIN_SUCCESS":
					loginFlag = true;
             
                
                // default would be the situation that receives INVALID_MESSAGE
                default:
                    log.info((String) JSONmsg.get("info"));
                    return true;
                }
        } catch (Exception e) {
            e.printStackTrace();
            //yuri
            log.error("Invalid Message:"+msg);
        }
            
        return true;
    
      }
    }
	
    
    
    // wei
    // use it when client wants to logout.
   
    
    
    // wei	
	  
//public void run(){
	       // try {
	            // create a scanner to get clients input
	            // I assume that clients input JSONObj.
	            //Scanner sc = new Scanner(System.in);
	            //String data;
	           // JSONParser parser = new JSONParser();
	            // send login command to server
	            //this.sendLogin();
	            //while(!term && (data = inreader.readLine()) != null) {
	                //term = this.process(data);
	            	    //ClientSkeleton.getInstance().process(this, data);
	                // getting activity obj from scanner and send it to server.
	                //try {
	                   // JSONObject JSONmsg = (JSONObject) parser.parse(sc.nextLine());
	                    //if (((String) JSONmsg.get("command")).equals("LOGOUT")) {
	                        //sendActivityObject(JSONmsg);
	                        //disconnect();
	                    //} else {
	                        // send whatever client types, let server handles it.
	                        //sendActivityObject(JSONmsg);
	                    //}                                        
	               // }  
	               // log.debug("connection closed to " + Settings.socketAddress(socket));
		           // input.close();
	       // }
	            //catch (ParseException ex) {
	                    //java.util.logging.Logger.getLogger(ClientSke"leton.class.getName()).log(Level.SEVERE, null, ex);
	               // }
	           
	            // Do I need to close the socket?
	            // socket.close();
	             //catch (IOException e) {
	            //log.error("connection " + Settings.socketAddress((socket)) + " closed with exception: " + e);
	       // }
	        
	       // open = false;
	   /// }
    
//}
