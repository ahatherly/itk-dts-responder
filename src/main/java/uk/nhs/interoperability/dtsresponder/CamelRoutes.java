package uk.nhs.interoperability.dtsresponder;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.SimpleTimeZone;
import java.util.UUID;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.builder.xml.Namespaces;
import org.apache.camel.component.file.GenericFile;
import org.apache.camel.component.properties.PropertiesComponent;
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
		 * This route will take whatever control files appear and try to read the corresponding dat file
		 */
		from("file://{{mailboxPath}}?move={{donePath}}&include={{filenamePattern}}")
		  .streamCaching()
		  // First, check this is a control file
		  .choice()
		  		.when().xpath("/DTSControl/MessageType = 'Data'")
			  		.log("CONTROL FILE FOUND - attempting to read data file")
			  		// Store the values from the control file so we can use them in our reply control file
			  		.setProperty("DONE_PATH", simple("{{donePath}}"))
			  		.setProperty("From_ESMTP",  xpath("/DTSControl/From_ESMTP").resultType(String.class))
			  		.setProperty("From_DTS",  xpath("/DTSControl/From_DTS").resultType(String.class))
			  		.setProperty("To_ESMTP",  xpath("/DTSControl/To_ESMTP").resultType(String.class))
			  		.setProperty("To_DTS",  xpath("/DTSControl/To_DTS").resultType(String.class))
			  		.setProperty("LocalId",  xpath("/DTSControl/LocalId").resultType(String.class))
			  		.setProperty("DTSId",  xpath("/DTSControl/DTSId").resultType(String.class))
			  		// Now read the corresponding data file, then process it
			  		.process(new readDataFile())
			  		.to("direct:handleDataFile")
			  .otherwise()
			  		// We received some other kind of message, so just log it and stop processing.
			  		.log("*********** Received an unexpected message, ignoring. *****************");
		
		
		from("direct:handleDataFile")
			.log("Processing data file...")
			.wireTap("direct:saveToDatabase")
			// Now, check this data file is a "SendCDA" document
		  		.choice()
			  		.when().xpath("/itk:DistributionEnvelope/itk:header/@service = 'urn:nhs-itk:services:201005:SendCDADocument-v2-0'", ns)
						// Get some more values from the data file to insert into our response data file
						.setProperty("RESPONDER_ADDRESS", simple("{{responderAddress}}"))
						.setProperty("RESPONDER_IDENTITY", simple("{{responderIdentity}}"))
						.setProperty("RECEIVER_ADDRESS",  xpath("/itk:DistributionEnvelope/itk:header/itk:addresslist/itk:address[1]/@uri").resultType(String.class).namespaces(ns))
						.setProperty("SENDER_ADDRESS",    xpath("/itk:DistributionEnvelope/itk:header/itk:senderAddress/@uri").resultType(String.class).namespaces(ns))
						.setProperty("TRACKING_ID",       xpath("/itk:DistributionEnvelope/itk:header/@trackingid").resultType(String.class).namespaces(ns))
						.setProperty("INTERACTION_ID",    xpath("/itk:DistributionEnvelope/itk:header/itk:handlingSpecification/itk:spec[@key='urn:nhs-itk:ns:201005:interaction']/@value").resultType(String.class).namespaces(ns))
						.setProperty("ORIG_PAYLOAD_ID",   xpath("/itk:DistributionEnvelope/itk:payloads/itk:payload/@id").resultType(String.class).namespaces(ns))
						.process(new addDynamicProperties())
						.wireTap("direct:sendInfAckIfRequested").end()
						.wireTap("direct:waitToSendBusAck").end()
					.otherwise()
		  		// We received some other kind of message, so just log it and stop processing.
		  		.log("*********** Received an unexpected message, ignoring. *****************");
		
		
		from("direct:sendInfAckIfRequested")
			.choice()	
				.when().xpath("/itk:DistributionEnvelope/itk:header/itk:handlingSpecification/itk:spec[@key='urn:nhs-itk:ns:201005:infackrequested']/@value = 'true'", ns)
				// Insert them into the velocity template
					.log("Writing Infrastructure ACK...")
					.to("velocity:inf-ack.vm")
					// Output the result file in the output path
					.to("file://{{outPath}}?fileName=response-${file:onlyname.noext}.dat")
					// And now insert the values into another template for the control file
					.log("Writing outgoing control file for Inf Ack...")
					.to("velocity:control-file.vm")
					.to("file://{{outPath}}?fileName=response-${file:onlyname.noext}.ctl")
					.log("Control file written")
				.otherwise()
					.log("We received a message, but no infrastructure ACK was requested.");
		
		from("direct:waitToSendBusAck")
			.delay(5000)
			.to("direct:sendBusAckIfRequested");
		
		from("direct:sendBusAckIfRequested")
			.choice()	
				.when().xpath("/itk:DistributionEnvelope/itk:header/itk:handlingSpecification/itk:spec[@key='urn:nhs-itk:ns:201005:ackrequested']/@value = 'true'", ns)
				    .log("Writing Business ACK...")
				    .to("velocity:bus-ack.vm")
				    // Output the result file in the output path
					.to("file://{{outPath}}?fileName=bus-response-${file:onlyname.noext}.dat")
					// And now insert the values into another template for the control file
					.log("Writing outgoing control file for Bus Ack...")
					.to("velocity:control-file.vm")
					.to("file://{{outPath}}?fileName=bus-response-${file:onlyname.noext}.ctl")
					.log("Control file written")
				.otherwise()
					.log("No business ACK was requested.");
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
		from("jetty:http://{{webGUIAddress}}?traceEnabled=true")
			.to("mongodb:mongoBean?database=myDB&collection=receivedDTSDocuments&operation=findAll")
			.to("velocity:messagesReceived.vm");
		
		/*
		 * Show a specific message
		 */
		from("jetty:http://{{webGUIAddress}}/message?traceEnabled=true")
			.setBody().simple("{\"_id\": {\"$oid\":\"${header.id}\"}}")
			.to("mongodb:mongoBean?database=myDB&collection=receivedDTSDocuments&operation=findOneByQuery");
    }
	
	
	// Simple class to generate a UUID
	public class addDynamicProperties implements Processor {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		public ThreadLocal<SimpleDateFormat> formatter;
		
		public void process(Exchange exchange) throws Exception {
	    
		  // Create UUIDs
		  exchange.setProperty("PAYLOAD_UUID", UUID.randomUUID().toString().toUpperCase());
		  exchange.setProperty("BUS_ACK_UUID", UUID.randomUUID().toString().toUpperCase());
	    
		  // Create a date stamp in UTC format
		  sdf.setTimeZone(new SimpleTimeZone(SimpleTimeZone.UTC_TIME, "UTC"));
		  exchange.setProperty("DATETIME", sdf.format(new Date()));
		  
		  // Create a date stamp in HL7 format
		  formatter = new ThreadLocal<SimpleDateFormat>() {
				protected SimpleDateFormat initialValue() {
					return new SimpleDateFormat("yyyyMMddHHmmss");
				}
			};
		  
		  exchange.setProperty("HL7DATETIME", formatter.get().format(new Date()));
	  }
	}
	
	// Simple class to read a data file, put it in the message body, and then move it to the done folder
	public class readDataFile implements Processor {
		public void process(Exchange exchange) throws Exception {
		  GenericFile f = (GenericFile)exchange.getProperty("CamelFileExchangeFile");
		  String dataFileName = f.getAbsoluteFilePath();
		  dataFileName = dataFileName.substring(0, dataFileName.lastIndexOf('.')) + ".dat";
		  String body = FileLoader.loadFile(dataFileName);
		  exchange.getIn().setBody(body);
		  
		  // Move the data file into the done path
		  String fileNameOnly = dataFileName.substring(dataFileName.lastIndexOf(File.separator));
		  Path source = Paths.get(dataFileName);
		  Path target = Paths.get((String)exchange.getProperty("DONE_PATH")+File.separator+fileNameOnly);
		  Files.move(source, target, REPLACE_EXISTING);
	  }
	}
}
