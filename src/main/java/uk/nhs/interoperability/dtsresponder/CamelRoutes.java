package uk.nhs.interoperability.dtsresponder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.SimpleTimeZone;
import java.util.UUID;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.builder.xml.Namespaces;
import org.apache.camel.model.dataformat.XmlJsonDataFormat;

public class CamelRoutes extends RouteBuilder {
	
	public void configure() {
		// Create a type converter object for converting XML to JSON
		XmlJsonDataFormat xmlJsonFormat = new XmlJsonDataFormat();
		// Create an object for our XML namespaces
		Namespaces ns = new Namespaces("itk", "urn:nhs-itk:ns:201005");
    	ns.add("hl7", "urn:hl7-org:v3");
    	ns.add("npfitlc", "NPFIT:HL7:Localisation");
		
		/*
		 * This route will take whatever .dat files appear and try to build an infrastructure ACK in response
		 */
		from("file://{{mailboxPath}}?move={{donePath}}&include={{filenamePattern}}")
		  .wireTap("direct:saveToDatabase")
		  
		  // First, check this is a "SendCDA" document
		  .choice()
		  		.when().xpath("/itk:DistributionEnvelope/itk:header/@service = 'urn:nhs-itk:services:201005:SendCDADocument-v2-0'", ns)
					// Set some values to insert into our response template
					.process(new createDynamicProperties())
					.setProperty("RESPONDER_ADDRESS", simple("{{recieverAddress}}"))
					.setProperty("RECEIVER_ADDRESS",  xpath("/itk:DistributionEnvelope/itk:header/itk:addresslist/itk:address[1]/@uri").resultType(String.class).namespaces(ns))
					.setProperty("SENDER_ADDRESS",    xpath("/itk:DistributionEnvelope/itk:header/itk:senderAddress/@uri").resultType(String.class).namespaces(ns))
					.setProperty("TRACKING_ID",       xpath("/itk:DistributionEnvelope/itk:header/@trackingid").resultType(String.class).namespaces(ns))
					// Insert them into the velocity template
					.to("velocity:inf-ack.vm")
					// Output the result file in the output path
					.to("file://{{outPath}}?fileName=response-${file:name}")
		  .otherwise()
		  		// We received some other kind of message, so just log it and stop processing.
		  		.log("Received an unexpected message, ignoring.");
		
		
		/*
		 * Take the content of the message and persist it to MongoDB
		 */
		from("direct:saveToDatabase")
			.marshal(xmlJsonFormat)
			.convertBodyTo(java.lang.String.class)
			.to("mongodb:mongoBean?database=myDB&collection=receivedDTSDocuments&operation=insert");
		
		
		/*
		 * Show a list of the messages that have been received (taken from MongoDB)
		 */
		from("jetty:http://0.0.0.0:8888?traceEnabled=true")
			.to("mongodb:mongoBean?database=myDB&collection=receivedDTSDocuments&operation=findAll")
			.to("velocity:messagesReceived.vm");
		
		/*
		 * Show a specific message
		 */
		from("jetty:http://0.0.0.0:8888/message?traceEnabled=true")
			.setBody().simple("{\"_id\": {\"$oid\":\"${header.id}\"}}")
			.to("mongodb:mongoBean?database=myDB&collection=receivedDTSDocuments&operation=findOneByQuery");
    }
	
	
	// Simple class to generate a UUID
	public class createDynamicProperties implements Processor {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		public void process(Exchange exchange) throws Exception {
	    
		  // Create a UUID
		  exchange.setProperty("PAYLOAD_UUID", UUID.randomUUID().toString().toUpperCase());
	    
		  // Create a date stamp in UTC format
		  sdf.setTimeZone(new SimpleTimeZone(SimpleTimeZone.UTC_TIME, "UTC"));
		  exchange.setProperty("DATETIME", sdf.format(new Date()));
	  }
	}
}
