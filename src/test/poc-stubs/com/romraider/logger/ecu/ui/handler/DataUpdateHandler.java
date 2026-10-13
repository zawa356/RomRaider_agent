package com.romraider.logger.ecu.ui.handler;
import com.romraider.logger.ecu.definition.LoggerData;
import com.romraider.logger.ecu.comms.query.Response;
public interface DataUpdateHandler {
 void registerData(LoggerData d);
 void deregisterData(LoggerData d);
 void cleanUp();
 void reset();
 void handleDataUpdate(Response r);
}
