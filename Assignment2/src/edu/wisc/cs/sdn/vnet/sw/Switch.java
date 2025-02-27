package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;

import java.util.HashMap;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
	}

	private HashMap<String, switchEntry> switchMap = new HashMap<>();

	private boolean debug = false;
	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		String srcString = etherPacket.getSourceMAC().toString();
		String dstString = etherPacket.getDestinationMAC().toString();

		switchEntry entry = switchMap.get(dstString);
		if (entry != null) {
			if (entry.isExpired()) {
				if (debug) System.out.println("Expired entry");
				switchMap.remove(dstString);
				broadcast(etherPacket, inIface);
			} else {
				if (debug) System.out.println("Sending packet");
				sendPacket(etherPacket, entry.getIface());
			}
		} else {
			if (debug) System.out.println("Broadcasting");
			broadcast(etherPacket, inIface);
		}
		
		entry = switchMap.get(srcString);
		if (entry == null) {
			if (debug) System.out.println("Adding mapping");
			switchMap.put(srcString, new switchEntry(inIface));
		} else {
			entry.updateTime();
		}

		if (debug) System.out.println(System.currentTimeMillis());
	}

	private void broadcast(Ethernet etherPacket, Iface inIface) {
		for (Iface curIface : interfaces.values()) {
			if (!curIface.equals(inIface)) {
				sendPacket(etherPacket, curIface);
			}
		}
	}
}

class switchEntry {
	private long time;
	private Iface iface;

	public switchEntry(Iface iface) {
		this.time = System.currentTimeMillis();
		this.iface = iface;
	}

	public boolean isExpired() {
		return ((System.currentTimeMillis() - this.time) > 15000);
	}

	public Iface getIface() {
		return this.iface;
	}

	public void updateTime() {
		this.time = System.currentTimeMillis();
	}
}
