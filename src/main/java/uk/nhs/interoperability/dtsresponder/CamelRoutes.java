package uk.nhs.interoperability.dtsresponder;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.SimpleTimeZone;
import java.util.UUID;

import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.builder.xml.Namespaces;
import org.apache.camel.component.file.GenericFile;
import org.apache.camel.model.dataformat.XmlJsonDataFormat;
import org.apache.commons.codec.binary.Base64;

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
    	//from("file://{{mailboxPath}}?noop=true&include={{filenamePattern}}")
		  .streamCaching()
		  // First, check this is a control file
		  .choice()
		  	.when().xpath("/DTSControl/MessageType = 'Data'")
			  		.log("CONTROL FILE FOUND - attempting to read data file")
			  		// Store the values from the control file so we can use them in our reply control file
			  		.setProperty("DONE_PATH", simple("{{donePath}}"))
			  		.setProperty("SENT_PATH", simple("{{sentPath}}"))
			  		.setProperty("TYPE", simple("Received"))
			  		.setProperty("From_ESMTP",  xpath("/DTSControl/From_ESMTP").resultType(String.class))
			  		.setProperty("From_DTS",  xpath("/DTSControl/From_DTS").resultType(String.class))
			  		.setProperty("To_ESMTP",  xpath("/DTSControl/To_ESMTP").resultType(String.class))
			  		.setProperty("To_DTS",  xpath("/DTSControl/To_DTS").resultType(String.class))
			  		.setProperty("LocalId",  xpath("/DTSControl/LocalId").resultType(String.class))
			  		.setProperty("DTSId",  xpath("/DTSControl/DTSId").resultType(String.class))
			  		// If we are simulating DTS, update the incoming file to include the transfer elements
			  		.wireTap("direct:updateIncomingControlFile").end()
			  		// Now read the corresponding data file, then process it
			  		.process(new readDataFile())
			  		.to("direct:handleDataFile")
			.otherwise()
			  		// We received some other kind of message, so just log it and stop .
			  		.log("*********** Received an unexpected message, ignoring. *****************");
		
    	from("direct:updateIncomingControlFile")
    	    .setHeader("simulateDTS", simple("{{simulateDTS}}"))
    		.choice()
		  		.when(header("simulateDTS").isEqualTo("true"))
		    		.convertBodyTo(java.lang.String.class)
		    		.process(new addTransferSuccessToIncomingControlFile())
		    		.log("Added transfer success elements to control file")
		    		.to("file:{{sentPath}}")
		    	.otherwise()
		    		.log("Not sumulating DTS");
    		
		
		
		from("direct:handleDataFile")
			.log(" data file...")
			.setHeader("xpathService", xpath("/itk:DistributionEnvelope/itk:header/@service").resultType(String.class).namespaces(ns))
			//.setHeader("messageType", simple("{{messageTypeToRespondTo}}"))
			.wireTap("direct:saveToDatabase")
			
			
			// Now, check this data file is a "SendCDA" document
		  	.choice()
		  		// Compare the allowed message type to the service attribute	
		  		.when(header("xpathService").isEqualTo(header("urn:nhs-itk:services:201005:sendDistEnvelope")))
		  				// Get some more values from the data file to insert into our response data file
		  				.setProperty("SERVICE", xpath("/itk:DistributionEnvelope/itk:header/@service").resultType(String.class).namespaces(ns))
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
		  		// We received some other kind of message, so just log it and stop .
		  		.log("*********** Received an unexpected message, ignoring. *****************");
		
		
		from("direct:sendInfAckIfRequested")
			.choice()	
				.when().xpath("/itk:DistributionEnvelope/itk:header/itk:handlingSpecification/itk:spec[@key='urn:nhs-itk:ns:201005:infackrequested']/@value = 'true'", ns)
				// Insert them into the velocity template
					.log("Writing Infrastructure ACK...")
					.setProperty("TYPE", simple("Sent"))
					.setProperty("trackingId", method(new UUIDGenerator()))
					.to("velocity:{{infAck}}.vm")
					.wireTap("direct:saveToDatabase").end()
					// Output the result file in the output path
					.to("file://{{outPath}}?fileName=${file:onlyname.noext}-infack.dat")
					// And now insert the values into another template for the control file
					.log("Writing outgoing control file for Inf Ack...")
					.to("velocity:control-file.vm")
					.to("file://{{outPath}}?fileName=${file:onlyname.noext}-infack.ctl")
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
				    .setProperty("TYPE", simple("Sent"))
				    .setProperty("trackingId", method(new UUIDGenerator()))
				    .to("velocity:{{busAck}}.vm")
				    .wireTap("direct:saveToDatabase").end()
				    // Output the result file in the output path
					.to("file://{{outPath}}?fileName=${file:onlyname.noext}-busack.dat")
					// And now insert the values into another template for the control file
					.log("Writing outgoing control file for Bus Ack...")
					.to("velocity:control-file.vm")
					.to("file://{{outPath}}?fileName=${file:onlyname.noext}-busack.ctl")
					.log("Control file written")
				.otherwise()
					.log("No business ACK was requested.");
		/*
		 * Take the content of the message and persist it to MongoDB
		 */
		from("direct:saveToDatabase")
			.setProperty("SENDER_ADDRESS",    xpath("/itk:DistributionEnvelope/itk:header/itk:senderAddress/@uri").resultType(String.class).namespaces(ns))
			.setProperty("TRACKING_ID",       xpath("/itk:DistributionEnvelope/itk:header/@trackingid").resultType(String.class).namespaces(ns))
			.setProperty("MESSAGE_TYPE",      xpath("/itk:DistributionEnvelope/itk:header/@service").resultType(String.class).namespaces(ns))
			//.to("xslt:addTimestamp.xslt")
			.process(new createMongoDBJsonDocument())
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
			.to("mongodb:mongoBean?database=myDB&collection=receivedDTSDocuments&operation=findOneByQuery")
			.process(new returnXMLMessageFromMongoDBJsonDocument());
		
		from("jetty:http://{{webGUIAddress}}/rendered?traceEnabled=true")
			.setBody().simple("{\"_id\": {\"$oid\":\"${header.id}\"}}")
			.to("mongodb:mongoBean?database=myDB&collection=receivedDTSDocuments&operation=findOneByQuery")
			.process(new returnCDADocumentFromMongoDBJsonDocument())
			.to("xslt:nhs_CDA_Document_Renderer.xsl");
		
		/*
		 * Clear all messages
		 */
		from("jetty:http://{{webGUIAddress}}/deleteall?traceEnabled=true")
			.setBody().constant("{\"Type\": \"Sent\"}")
			.to("mongodb:mongoBean?database=myDB&collection=receivedDTSDocuments&operation=remove")
			.setBody().constant("{\"Type\": \"Received\"}")
			.to("mongodb:mongoBean?database=myDB&collection=receivedDTSDocuments&operation=remove")
			.to("velocity:messagesDeleted.vm");
		
		/*
		 * Serve up required JS and CSS files
		 */
		from("jetty:http://{{webGUIAddress}}/js/jquery-1.12.0.min.js")
			.process(new Processor() {
				  public void process(Exchange exchange) throws Exception {
				    exchange.getOut().setHeader("Content-Type", "application/javascript");
				    exchange.getOut().setBody(getClass().getResourceAsStream("/js/jquery-1.12.0.min.js"));
				  }
			});
		from("jetty:http://{{webGUIAddress}}/js/jquery.dataTables.min.js")
			.process(new Processor() {
				  public void process(Exchange exchange) throws Exception {
				    exchange.getOut().setHeader("Content-Type", "application/javascript");
				    exchange.getOut().setBody(getClass().getResourceAsStream("/js/jquery.dataTables.min.js"));
				  }
			});
		from("jetty:http://{{webGUIAddress}}/css/jquery.dataTables.min.css")
			.process(new Processor() {
				  public void process(Exchange exchange) throws Exception {
				    exchange.getOut().setHeader("Content-Type", "text/css");
				    exchange.getOut().setBody(getClass().getResourceAsStream("/css/jquery.dataTables.min.css"));
				  }
			});
	
    }
	
	
	// Simple class to generate a UUID
	public class addDynamicProperties implements Processor {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		public ThreadLocal<SimpleDateFormat> formatter;
		
		public void process(Exchange exchange) throws Exception {
	    
		  // Create UUIDs
		  exchange.setProperty("PAYLOAD_UUID", UUID.randomUUID().toString().toUpperCase());
		  exchange.setProperty("BUS_ACK_UUID", UUID.randomUUID().toString().toUpperCase());
		  exchange.setProperty("ERROR_UUID", UUID.randomUUID().toString().toUpperCase());
		  
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
		  Path target = Paths.get((String)exchange.getProperty("SENT_PATH")+File.separator+fileNameOnly);
		  Files.move(source, target, REPLACE_EXISTING);
	  }
	}
	
	// Create a JSON document to store in MongoDB with some metadata taken from the message, and
	// the message itself embedded in a field in the JSON in Base64 format
	public class createMongoDBJsonDocument implements Processor {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		public void process(Exchange exchange) throws Exception {
			String body = exchange.getIn().getBody().toString();
			StringBuilder sb = new StringBuilder();
			String b64 = new String(Base64.encodeBase64(body.getBytes()));
			sb.append("{")
				.append("\"Type\":\"").append(exchange.getProperty("TYPE")).append("\",")
				.append("\"DateTime\":\"").append(sdf.format(new Date())).append("\",")
				.append("\"TrackingID\":\"").append(exchange.getProperty("TRACKING_ID")).append("\",")
				.append("\"MessageType\":\"").append(exchange.getProperty("MESSAGE_TYPE")).append("\",")
				.append("\"Sender\":\"").append(exchange.getProperty("SENDER_ADDRESS")).append("\",")
				.append("\"MessageB64\":\"").append(b64).append("\"")
			  .append("}");
			exchange.getIn().setBody(sb.toString());
		}
	}
	
	// Create a JSON document to store in MongoDB with some metadata taken from the message, and
	// the message itself embedded in a field in the JSON in Base64 format
	public class returnXMLMessageFromMongoDBJsonDocument implements Processor {
		public void process(Exchange exchange) throws Exception {
			String body = exchange.getIn().getBody().toString();
			StringBuilder sb = new StringBuilder();
			String searchText = "MessageB64\" : \"";
			int startOfB64 = body.indexOf(searchText)+searchText.length();
			int endOfB64 = body.indexOf('"', startOfB64);
			//String b64 = new String(Base64.encodeBase64(body.getBytes()));
			String b64 = body.substring(startOfB64, endOfB64);
			String xml = new String(Base64.decodeBase64(b64));
			exchange.getIn().setBody(xml);
		}
	}
	
	// Edit the file to add the transfer success (used when simulating DTS)
	public class addTransferSuccessToIncomingControlFile implements Processor {
		public void process(Exchange exchange) throws Exception {
			String body = exchange.getIn().getBody().toString();
			System.out.println(body);
			StringBuilder sb = new StringBuilder();
			String searchText = "</DTSControl>";
			int startOfFinalTag = body.indexOf(searchText);
			sb.append(body.substring(0, startOfFinalTag));
			sb.append("<StatusRecord><DateTime>20151023154813</DateTime><Event>TRANSFER</Event><Status>SUCCESS</Status><StatusCode>00</StatusCode><Description>Transferred to recipient mailbox</Description></StatusRecord>");
			sb.append(body.substring(startOfFinalTag));
			exchange.getIn().setBody(sb.toString());
		}
	}
	
	// Create a JSON document to store in MongoDB with some metadata taken from the message, and
	// the message itself embedded in a field in the JSON in Base64 format
	public class returnCDADocumentFromMongoDBJsonDocument implements Processor {
		public void process(Exchange exchange) throws Exception {
			// First, extract the XML
			returnXMLMessageFromMongoDBJsonDocument proc = new returnXMLMessageFromMongoDBJsonDocument();
			proc.process(exchange);
			
			// Now, find the CDA part
			String body = exchange.getIn().getBody().toString();
			String searchText = "ClinicalDocument";
			int startOfCDA = body.indexOf(searchText);
			int endOfCDA = body.indexOf(searchText, (startOfCDA+searchText.length()));
			// Backtrack the start tag to the opening <
			while (body.charAt(startOfCDA) != '<') {
				startOfCDA--;
			}
			// Step forward the end tag to the closing >
			while (body.charAt(endOfCDA) != '>') {
				endOfCDA++;
			}
			endOfCDA++;
			
			if (startOfCDA > -1 && endOfCDA > startOfCDA) {
				String cda = body.substring(startOfCDA, endOfCDA);
				exchange.getIn().setBody(cda);
			} else {
				exchange.getIn().setBody("Unable to locate CDA document in message");
			}
		}
	}
	
	public static class UUIDGenerator {
		public String generateId() {
			return java.util.UUID.randomUUID().toString().toUpperCase();
		}
	}
}
