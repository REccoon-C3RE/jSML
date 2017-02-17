/*
    Copyright (C) 2017  REccoon

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.

	@author REccoon@posteo.de
	
	
    This program is based on jSML library.
    For more information visit http://www.openmuc.org
 */

import gnu.io.PortInUseException;
import gnu.io.UnsupportedCommOperationException;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import org.openmuc.jsml.structures.ASNObject;
import org.openmuc.jsml.structures.Integer64;
import org.openmuc.jsml.structures.SML_File;
import org.openmuc.jsml.structures.SML_GetListRes;
import org.openmuc.jsml.structures.SML_List;
import org.openmuc.jsml.structures.SML_ListEntry;
import org.openmuc.jsml.structures.SML_Message;
import org.openmuc.jsml.structures.SML_MessageBody;
import org.openmuc.jsml.structures.SML_Unit;
import org.openmuc.jsml.structures.SML_Value;
import org.openmuc.jsml.tl.SML_SerialReceiver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
	read data from electricity meter EMH EDL300L
	@see: http://wiki.c3re.de/index.php?title=Projekt_23_Smarthome_/_Zugriff_Stromz%C3%A4hler
*/
public class EDL300Lreader {

	public static String TARIFF_180= "0x01 0x00 0x01 0x08 0x00 0xff";
	public static String TARIFF_280= "0x01 0x00 0x02 0x08 0x00 0xff";
	// currently not used:
	// public static String TARIFF_181= "0x01 0x00 0x01 0x08 0x01 0xff";
	// public static String TARIFF_182= "0x01 0x00 0x01 0x08 0x02 0xff";
	// public static String TARIFF_281= "0x01 0x00 0x02 0x08 0x01 0xff";
	// public static String TARIFF_282= "0x01 0x00 0x02 0x08 0x02 0xff";
	
	private final static Logger LOGGER = Logger.getLogger( EDL300Lreader.class.getName());
	
	private static Properties loadProperties() throws IOException
	{
		Properties properties = new Properties();
		InputStream input = new FileInputStream( "config.properties");
		properties.load(input);
		return properties;
	}

	private static void insertDatabase( Connection connection, long value_180, long value_280) throws SQLException
	{
        PreparedStatement preparedStatement = 
        	connection.prepareStatement("insert into sml.meter (value_180, value_280) values (?, ?)");
        preparedStatement.setLong(1, value_180);
        preparedStatement.setLong(2, value_280);
        preparedStatement.executeUpdate();
	}
	
	public static void usage()
	{
		StringBuilder builder = new StringBuilder( "read data from electricity meter EMH EDL300L\n");
		builder.append( "see: http://wiki.c3re.de/index.php?title=Projekt_23_Smarthome_/_Zugriff_Stromz%C3%A4hler\n\n");
		builder.append( "usage:\n");
		builder.append("  database : insert into configured database (see config.properties)");
		builder.append("\n\n");
		System.out.println( builder);
	}
	
	public static void main(String[] args) throws IOException, PortInUseException
		, UnsupportedCommOperationException, SQLException {
		if ("?".equals( args[0]) || "help".equals( args[0]))
			usage();
		else
		{
			Properties properties= loadProperties();
			
			// initialize log level
			String level= properties.getProperty( "loglevel", "WARNING");
			LOGGER.setLevel( Level.parse( level));
		
			// using IR-reader from project 'volkszaehler' (Udo) which is connected via USB
			// http://wiki.volkszaehler.org/hardware/controllers/ir-schreib-lesekopf-usb-ausgang
			SML_SerialReceiver receiver = new SML_SerialReceiver();
			receiver.setupComPort(  properties.getProperty( "ir-device", "/dev/ttyUSB0"));
			
			// the meter values
			long value_180=0;   // tariff 1.8.0: counter for incoming power (from public grid)
			long value_280=0;	// tariff 2.8.0: counter for outgoing power (into public grid)
			
			SML_File smlFile = receiver.getSMLFile();
	
			List<SML_Message> smlMessages = smlFile.getMessages();
			
			for (int i = 0; i < smlMessages.size(); i++) {
				SML_Message sml_message = smlMessages.get(i);
	
				int tag = sml_message.getMessageBody().getTag().getVal();
				if (tag==SML_MessageBody.GetListResponse)
				{
					SML_GetListRes resp = (SML_GetListRes) sml_message.getMessageBody().getChoice();
					SML_List smlList = resp.getValList();
					SML_ListEntry[] list = smlList.getValListEntry();
					for (SML_ListEntry entry : list) {
						SML_Value value = entry.getValue();
						ASNObject obj = value.getChoice();
	
						if (obj.getClass().equals(Integer64.class)) {
							SML_Unit unit= entry.getUnit();
							ASNObject choice= entry.getValue().getChoice();
							if ((choice != null) && (choice instanceof Integer64) && (SML_Unit.WATT_HOUR == unit.getVal()))
							{
								long data= ((Integer64) choice).getVal();
								StringBuilder builder = new StringBuilder();
								for (byte element : entry.getObjName().getOctetString())
									builder.append(String.format("0x%02x ", element));
								String ident= builder.toString().trim();
								if (TARIFF_180.equals( ident))
								{
									LOGGER.info( "Tariff 1.8.0: " + data);
									value_180= data;
								}
								else if (TARIFF_280.equals( ident))
								{
									LOGGER.info( "Tariff 2.8.0: " + data);
									value_280= data;
								}
								else LOGGER.info( "Tariff not handled: " + ident + " - " + data);
							}
						}
					}
					break;
				}
			}
			receiver.close();
	
			for (String s: args) {
				if ("database".equals(s))	{
					if (value_180>0 && value_280>0)
					{
				        Connection connection= DriverManager.getConnection( properties.getProperty( "database.mysql.connection"));
						insertDatabase( connection, value_180, value_280);
						connection.close();
						System.out.println( "Insert into database: 1.8.0=" + value_180 + " - 2.8.0=" + value_280);
					}
					else
						LOGGER.warning( "No values written (value <= 0)");
				}
				if ("json".equals(s))
					System.out.println( "{ \"timestamp\": " + System.currentTimeMillis() 
						+ " , \"1.8.0\": " + value_180 + " , \"2.8.0\": " + value_280 + " }");
	        }
		}
	}
}
