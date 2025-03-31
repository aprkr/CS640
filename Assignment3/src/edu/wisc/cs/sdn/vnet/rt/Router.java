package edu.wisc.cs.sdn.vnet.rt;

import java.util.*;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;
import net.floodlightcontroller.packet.UDP;

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

	private RIPv2 rip;

	private int RIP_IP = IPv4.toIPv4Address("224.0.0.9");
	private String RIP_MAC = "ff:ff:ff:ff:ff:ff";
	
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

	public void initRip() {
		rip = new RIPv2();
		for (Iface iface : interfaces.values()) {
			int mask = iface.getSubnetMask();
			int address = iface.getIpAddress() & mask;
			RIPv2Entry entry = new RIPv2Entry(address, mask, 0);
			rip.addEntry(entry);

			RouteEntry routeEntry = new RouteEntry(address, 0, mask, iface);
			routeEntry.setRip(entry);
			routeTable.insert(routeEntry);

			sendRip(RIPv2.COMMAND_REQUEST, iface, null);
		}


		TimerTask unsolTimerTask = new TimerTask() {
			public void run() {
				for (Iface iface : interfaces.values()) {
					sendRip(RIPv2.COMMAND_UNSOLICITED, iface, null);
				}
			}
		};

		unsolTimerTask.run();

		TimerTask timeoutTimerTask = new TimerTask() {
			public void run() {
				List<RIPv2Entry> entries = rip.getEntries();
				List<RIPv2Entry> toRemove = new LinkedList<RIPv2Entry>();
				for (RIPv2Entry entry : entries) {
					if (entry.getMetric() > 0 && ((System.currentTimeMillis() - entry.getTimeStamp()) >= 30000)) {
						if (debug) System.out.println("Removing RIP entry");
						toRemove.add(entry);
						routeTable.remove(entry.getAddress(), entry.getSubnetMask());
					}
				}
				entries.removeAll(toRemove);
			}
		};

		Timer timer = new Timer(true);
		timer.schedule(timeoutTimerTask, 0, 1000);
		timer.schedule(unsolTimerTask, 0, 10000);
	}

	private void sendRip(byte type, Iface iface, Ethernet requesterEther) {
		Ethernet ether = new Ethernet();
		IPv4 ip = new IPv4();
		UDP udp = new UDP();

		RIPv2 sendRiPv2 = (RIPv2)rip.clone();

	
		ether.setPayload(ip);
		ip.setPayload(udp);
		udp.setPayload(sendRiPv2);

		ether.setSourceMACAddress(iface.getMacAddress().toBytes());
		ether.setEtherType(Ethernet.TYPE_IPv4);

		ip.setTtl((byte)32);
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setSourceAddress(iface.getIpAddress());

		udp.setSourcePort(UDP.RIP_PORT);
		udp.setDestinationPort(UDP.RIP_PORT);

		switch (type) {
			case RIPv2.COMMAND_UNSOLICITED:
				sendRiPv2.setCommand(RIPv2.COMMAND_RESPONSE);
				ether.setDestinationMACAddress(RIP_MAC);
				ip.setDestinationAddress(RIP_IP);
				break;
			case RIPv2.COMMAND_REQUEST:
				sendRiPv2.setCommand(RIPv2.COMMAND_REQUEST);
				ether.setDestinationMACAddress(RIP_MAC);
				ip.setDestinationAddress(RIP_IP);
				break;
			case RIPv2.COMMAND_RESPONSE:
				IPv4 requesterIP = (IPv4)requesterEther.getPayload();
				sendRiPv2.setCommand(RIPv2.COMMAND_RESPONSE);
				ether.setDestinationMACAddress(requesterEther.getSourceMACAddress());
				ip.setDestinationAddress(requesterIP.getSourceAddress());
				break;
			default:
				break;
		}

		sendPacket(ether, iface);
		
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

					if (packet.getProtocol() == IPv4.PROTOCOL_UDP) {
							UDP udp = (UDP)packet.getPayload();
							if (udp.getDestinationPort() == UDP.RIP_PORT) {
								handleRip(etherPacket, inIface);
								return;
							}
					}

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

	private void handleRip(Ethernet ether, Iface iface) {
		IPv4 packet = (IPv4)ether.getPayload();
		UDP udp = (UDP)packet.getPayload();
		RIPv2 rip = (RIPv2)udp.getPayload();
		if (rip.getCommand() == RIPv2.COMMAND_REQUEST) {
			if (debug) System.out.println("Responding to RIP");
			sendRip(RIPv2.COMMAND_RESPONSE, iface, ether);
		} else { // A response, incorporate new entries
			List<RIPv2Entry> entries = rip.getEntries();
			boolean updated = false;
			for (RIPv2Entry entry : entries) {
				int addr = entry.getAddress();
				int mask = entry.getSubnetMask();
				int next = packet.getSourceAddress();
				int metric = entry.getMetric() + 1;
				int net = addr & mask;

				RouteEntry routeEntry = routeTable.lookup(net);
				if (routeEntry != null) {
					RIPv2Entry myEntry = routeEntry.getRip();
					if (metric < myEntry.getMetric()) {
						if (debug) System.out.println("Better RIP found");
						myEntry.setMetric(metric);
						routeEntry.setInterface(iface);
						routeEntry.setGatewayAddress(next);
						updated = true;
						myEntry.updateTimeStamp();
					} else if (metric > 16) {
						if (debug) System.out.println("Metric too large, removing RIP entry");
						// if (iface.equals(routeEntry.getInterface())) {
							this.rip.getEntries().remove(myEntry);
							routeTable.remove(addr, mask);
							updated = true;
						// }
					} else if ((metric == myEntry.getMetric()) && (routeEntry.getInterface() == iface)) {
						if (debug) System.out.println("Updating RIP entry timestamp");
						myEntry.updateTimeStamp();
					}
				} else {
					RIPv2Entry newRiPv2Entry = new RIPv2Entry(addr, mask, metric);
					this.rip.addEntry(newRiPv2Entry);
					RouteEntry newRouteEntry = new RouteEntry(addr, next, mask, iface);
					newRouteEntry.setRip(newRiPv2Entry);
					routeTable.insert(newRouteEntry);
					updated = true;
				}
			}

			if (updated) { // Broadcast RIP updates
				if (debug) System.out.println("RIP updated");
				for (Iface routIface : interfaces.values()) {
					if (routIface != iface) {
						sendRip(RIPv2.COMMAND_UNSOLICITED, routIface, ether);
					}
				}
			}
		}
	}
}
