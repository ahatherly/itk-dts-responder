package uk.nhs.interoperability.dtsresponder;

import org.apache.camel.component.properties.PropertiesComponent;
import org.apache.camel.main.Main;

public class StartResponder {
	
	public static void main(String[] args) throws Exception {
		// create a Main instance
        Main main = new Main();
        // enable hangup support so you can press ctrl + c to terminate the JVM
        main.enableHangupSupport();
        // bind MyBean into the registery
        //main.bind("foo", new MyBean());
        PropertiesComponent pc = new PropertiesComponent();
        pc.setLocation("classpath:responder.properties");
        main.bind("properties", pc);
        // add routes
        main.addRouteBuilder(new CamelRoutes());
        // run until you terminate the JVM
        System.out.println("Starting Camel. Use ctrl + c to terminate the JVM.\n");
        main.run();
	}
	
}
