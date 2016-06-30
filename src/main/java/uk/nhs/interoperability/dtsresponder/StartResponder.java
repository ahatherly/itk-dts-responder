package uk.nhs.interoperability.dtsresponder;

import org.apache.camel.component.properties.PropertiesComponent;
import org.apache.camel.main.Main;

import com.mongodb.MongoClient;

public class StartResponder {
	
	public static void main(String[] args) throws Exception {
		// Create a Main instance
        Main main = new Main();
        // Enable hangup support so you can press ctrl + c to terminate the JVM
        main.enableHangupSupport();
        
        // Bind some configuration values into Camel so they are available in routes
        PropertiesComponent pc = new PropertiesComponent();
        pc.setLocation("file:responder.properties");
        main.bind("properties", pc);

        // Add the MongoDB bean into the registry (no hostname or port required for localhost)
        main.bind("mongoBean", new MongoClient());
        
        // Add routes
        main.addRouteBuilder(new CamelRoutes());
        
        // Run until you terminate the JVM
        System.out.println("Starting Camel. Use ctrl + c to terminate the JVM.\n");
        main.run();
	}
	
}
