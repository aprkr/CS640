import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

public class TCPend {
    
	private static short myPort;
	private static String remoteIP;
	private static short remotePort;
	private static String fileName = null;
	private static short mtu;
	private static short sws;
	private static boolean isSender = false;
	private static File file;

    public static void main(String[] args) {

		

        for(int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.equals("-h")) {
				usage();
				return;
			} else if (arg.equals("-p")) {
				myPort = Short.parseShort(args[++i]); 
			} else if (arg.equals("-a")) {
				remotePort = Short.parseShort(args[++i]);
				isSender = true;
			} else if (arg.equals("-s")) {
				remoteIP = args[++i];
				isSender = true;
			} else if (arg.equals("f")) {
				fileName = args[++i];
			} else if (arg.equals("-m")) {
				mtu = Short.parseShort(args[++i]);
			} else if (arg.equals("-c")) {
				sws = Short.parseShort(args[++i]);
			}
		}
		file = new File(fileName);
			
		if (isSender) {
			sender();
		} else {
			receiver();
		}
    }

	static void sender() {
		try {
			FileInputStream stream = new FileInputStream(file);
			byte[] fileBuf = stream.readAllBytes();
			int currentByte = 0;
			int fileLength = fileBuf.length;
			DatagramSocket socket = new DatagramSocket(myPort);
			InetAddress netAddress = InetAddress.getByName(remoteIP);

			// Create connection

			while (currentByte < fileLength) {
				// Prep TCP packet
				TCP tcp = new TCP();
				DatagramPacket TCPpacket = new DatagramPacket(fileBuf, fileLength, netAddress, remotePort);
				// Send
				socket.send(TCPpacket);
				try { // Wait for ACK
					socket.setSoTimeout(1000);
					socket.receive(TCPpacket);
				} catch (SocketTimeoutException e) { // If no ACK
					System.out.println("No ACK");
				}

			}

			stream.close();
			socket.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	static void receiver() {
		try {
			FileOutputStream outputStream = new FileOutputStream(file);
			TCP recTCP = new TCP();
			byte[] byteBuf = new byte[mtu];
			DatagramPacket recPacket = new DatagramPacket(byteBuf, mtu);
			DatagramSocket socket = new DatagramSocket(myPort);
			InetAddress netAddress = InetAddress.getByName("localhost");

			// Wait for SYN

			// Find IP and sendSocket


			while (recTCP.getFlags() != 1) {
				socket.receive(recPacket);
				System.out.println("Packet received");
			}

			outputStream.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

    static void usage()
	{
		System.out.println("java TCPend -p <port> -s <remote IP> -a <remote port> f <file name> -m <mtu> -c <sws>");
		System.out.println("java TCPend -p <port> -m <mtu> -c <sws> -f <file name>");
	}
}
