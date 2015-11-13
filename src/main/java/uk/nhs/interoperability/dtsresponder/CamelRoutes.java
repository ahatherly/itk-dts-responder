package uk.nhs.interoperability.dtsresponder;

import java.util.UUID;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

public class CamelRoutes extends RouteBuilder {
	
	private String mailboxPath;
	
	public void configure() {
		from("file://{{mailboxPath}}?move={{donePath}}&include={{filenamePattern}}")
		  //.setHeader("PAYLOAD_UUID").simple("${exchangeId}")
		  .process(new createUUID())
		  .to("velocity:inf-ack.vm")
		  .to("file://{{outPath}}");
    }
	
	public class createUUID implements Processor {
	  public void process(Exchange exchange) throws Exception {
	    exchange.setProperty("PAYLOAD_UUID", UUID.randomUUID().toString().toUpperCase());
	  }
	}
}
