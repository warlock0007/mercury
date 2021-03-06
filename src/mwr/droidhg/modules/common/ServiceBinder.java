import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InputStream;
import android.os.Message;
import android.os.Messenger;
import android.content.Intent;
import android.content.ServiceConnection;
import android.app.Activity;
import android.content.Context;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.lang.ref.WeakReference;

/* Mercury ServiceBinder
 * 
 * The ServiceBinder provides synchronous interaction with services, exported by
 * other Android apps.
 * 
 * To use the ServiceBinder, create an instance and call execute(). This will
 * bind to the service and send the message, before waiting for the result.
 * 
 * If a response was received, #execute() returns True; otherwise False. The
 * ServiceBinder will wait for twenty seconds before a timeout.
 * 
 * If #execute() returned True, you can access the response Message through
 * #getMessage(), and its attached Bundle through #getData().
 */
public class ServiceBinder {

    Message in;

    //this is used to recieve a response from the handler
    volatile Message response = null;
    private Message returnMessage = null;
    private Bundle returnBundle = null;

    //lock object to wait fro a response from the service
    public volatile Object lock = new Object();
    
    //connection handler
    HgServiceConnection serviceConnection;

    public boolean execute(Context context, String package_name, String class_name, Message message) {
        HandlerThread thread = new HandlerThread("MercuryHandler", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        Looper serviceLooper = thread.getLooper();

        serviceConnection = new HgServiceConnection(serviceLooper, this);

        ComponentName c = new ComponentName(package_name, class_name);

        in = message;
        
        if(c == null)
            return false;

        Intent i = new Intent();
        i.setComponent(c);
        
        // bind to the service, and wait for the send/receive to finish
        synchronized(lock){
            context.bindService(i, serviceConnection, Context.BIND_AUTO_CREATE);
            
            try {
                lock.wait(20000);
            }
            catch(InterruptedException e){
                return false;
            }
        }
        
        // unbind from the service, so we don't leak handles
        context.unbindService(serviceConnection);
        
        // if a response was received, store it in our local variables
        if(response != null) {
            this.returnMessage = Message.obtain(this.response);
            this.returnBundle = this.returnMessage.getData();
            
            return true;
        }
        else {
            return false;
        }
    }

    // when message is reutnred to the python, the bundle is not returned with it.
    // this method allows you to do so
    public Bundle getData(){
        return this.returnBundle;
    }
    
    // return the message to client side
    public Message getMessage(){
        return this.returnMessage;
    }

    private class HgServiceConnection extends Handler implements ServiceConnection {

        Messenger serviceMessenger = null;
        Messenger responseMessenger = new Messenger(this);

        private WeakReference<ServiceBinder> sb;
    
        public HgServiceConnection(Looper looper, ServiceBinder sb){
            super(looper);
            
            this.sb = new WeakReference<ServiceBinder>(sb);
        }

        public void onServiceConnected(ComponentName className, IBinder service) {
	        //service has been connected to this, send the given message
	        serviceMessenger = new Messenger(service);
	        sendToService(this.sb.get().in);
	    }
	    
	    public void onServiceDisconnected(ComponentName className) {
	        //error during connection / something went wrong during binding.
            //notify lock, since we have not set response to something, the main thread
            //will recognize that something has gone wrong
            this.sb.get().lock.notifyAll();
	    }

        @Override
        public void handleMessage(Message msg){
            this.sb.get().response = Message.obtain(msg);
            
            synchronized(this.sb.get().lock){
                try {
                    this.sb.get().lock.notifyAll();
                }
                catch(IllegalMonitorStateException e){}
            }
        }

        public void sendToService(Message msg){
            msg.replyTo = responseMessenger;
            try {
                serviceMessenger.send(msg);
            }
            catch(Exception e){}
        }
    
    }

}
