package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	private boolean debug = false;
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		if (debug && (etherPacket.getEtherType() == -31011)) {
			return;
		}
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		if (etherPacket.getEtherType() == Ethernet.TYPE_IPv4) {
			IPv4 packet = (IPv4) etherPacket.getPayload();
			short actualCheck = packet.getChecksum();
			packet.setChecksum((short)0);
			
			byte[] serial = packet.serialize();
			packet.deserialize(serial, 0, serial.length);

			if (actualCheck == packet.getChecksum()) {
				byte ttl = packet.getTtl();
				if (ttl > (byte)1) {
					packet.setTtl((byte)(ttl - 1));
					// Recompute Checksum on new TTL
					packet.setChecksum((short)0);
					serial = packet.serialize();
					packet.deserialize(serial, (short)0, serial.length);

					etherPacket.setPayload(packet);
					if (debug) System.out.println("Checksum and TTL good");


					for (Iface iface : interfaces.values()) {
						if (iface.getIpAddress() == packet.getDestinationAddress()) {
							return;
						}
					}

					if (debug) System.out.println("Forwarding packet");

					// Packet not meant for router itself, forward to different interface
					RouteEntry entry = routeTable.lookup(packet.getDestinationAddress());

					if (entry != null) {
						ArpEntry arpent = null;
						if (entry.getGatewayAddress() == 0) {
							if (debug) System.out.println("Same subnet");
							arpent = arpCache.lookup(packet.getDestinationAddress());
						} else {
							if (debug) System.out.println("Different subnet");
							arpent = arpCache.lookup(entry.getGatewayAddress());
						}
						
						if (arpent != null) {
							while (entry.getInterface().getMacAddress() == null) { }
							etherPacket.setSourceMACAddress(entry.getInterface().getMacAddress().toBytes());
							etherPacket.setDestinationMACAddress(arpent.getMac().toBytes());
							if (debug) System.out.println("Forwarding to MAC: " + etherPacket.getDestinationMAC() + " from MAC: " + etherPacket.getSourceMAC());
							sendPacket(etherPacket, entry.getInterface());
						}
					}
				}
			}
		}
	}
}
