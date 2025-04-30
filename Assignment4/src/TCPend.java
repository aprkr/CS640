import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class TCPend {
    
	private static final short ACK = 1;
	private static final short FIN = 2;
	private static final short SYN = 4;
	private static short myPort;
	private static String remoteIP;
	private static short remotePort;
	private static String fileName = null;
	private static short mtu;
	private static short sws;
	private static boolean isSender = false;
	private static File file;
	private static long startTime = System.currentTimeMillis();
	private static short segsInFlight = 0;
	private static int TCPdataSize = 0;

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
		TCPdataSize = mtu - TCP.HEADER_LENGTH;
			
		if (isSender) {
			sender();
		} else {
			receiver();
		}
    }

	static void sender() {
		try {
			FileInputStream stream = new FileInputStream(file);
			// byte fileBuf[] = new byte[mtu];
			byte[] fileBuf = stream.readAllBytes();
			byte dataBuf[] = new byte[TCPdataSize];
			
			int dataBufLength = 0;
			DatagramSocket socket = new DatagramSocket(myPort);
			InetAddress netAddress = InetAddress.getByName(remoteIP);
			byte[] datagramBytes = new byte[mtu];
			DatagramPacket packet = new DatagramPacket(datagramBytes, mtu);
			TCP tcp = new TCP( 0, 0, null,SYN);
			int retransmits = 0;
			int sequence = 0;
			int ack = 0;
			int recAck = 0;

			// Create connection
			socket.setSoTimeout(2000);
			while (true) {
				sendPacket(socket, netAddress, tcp);
				try {
					TCP synacktcp = receivePacket(socket, packet);
					if ((synacktcp.getFlags() ^ (SYN | ACK)) == 0) {
						retransmits = 0;
						sequence = 1;
						ack = 1;
						break;
					}
				} catch (SocketTimeoutException e) {
					retransmits++;
					if (retransmits >= 15) {
						System.err.println("Too many retransmits, quitting");
						return;
					}
				}
			}
			tcp = new TCP(sequence, ack, null, ACK);
			recAck = 1;
			sendPacket(socket, netAddress, tcp);
			while (recAck < fileBuf.length - 1) { // Send file data
				if (retransmits == 0) {
					
				} else if (retransmits >= 16) {
					System.err.println("Too many retransmits, quitting");
					return;
				}

				while (segsInFlight < sws) {
					// dataBuf = stream.readNBytes(mtu - TCP.HEADER_LENGTH);
					// if (dataBuf.length < (mtu - TCP.HEADER_LENGTH)) {
					// 	mtu = (short)(dataBuf.length + TCP.HEADER_LENGTH); // Dirty
					// }
					dataBufLength = (fileBuf.length - (sequence - 1));
					if (dataBufLength < TCPdataSize) {
					} else {
						dataBufLength = TCPdataSize;
					}
					dataBuf = Arrays.copyOfRange(fileBuf, sequence - 1, dataBufLength + sequence - 1);
					// System.arraycopy(fileBuf, sequence - 1, dataBuf, 0, dataBufLength);
					tcp = new TCP(sequence, ack, dataBuf, 0);
					sendPacket(socket, netAddress, tcp);
					sequence += dataBufLength;
					segsInFlight++;
				}
				

				try { // Wait for ACK
					socket.setSoTimeout(1100);
					tcp = receivePacket(socket, packet);
					int segsAcked = (TCPdataSize + tcp.getAcknowledge() - sequence) / TCPdataSize;
					segsInFlight -= segsAcked;
					recAck = tcp.getAcknowledge();
					retransmits = 0;
				} catch (SocketTimeoutException e) { // If no ACK
					System.out.println("No ACK");
					retransmits++;
				}

			}

			// Close connection
			while (true) {
				tcp = new TCP(sequence, ack, null, FIN);
				sendPacket(socket, netAddress, tcp);
				tcp = receivePacket(socket, packet);
				if ((tcp.getFlags() ^ (FIN | ACK)) == 0) {
					ack++;
					tcp = new TCP(sequence, ack, null, ACK);
					sendPacket(socket, netAddress, tcp);
					break;
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
			TCP tcp = new TCP(0, 0, null, 0);
			byte[] datagramBytes = new byte[mtu];
			DatagramPacket recPacket = new DatagramPacket(datagramBytes, mtu);
			DatagramSocket socket = new DatagramSocket(myPort);
			InetAddress netAddress = InetAddress.getByName("localhost");
			int sequence = 0;
			int ack = 0;

			// Wait for SYN
			while ((tcp.getFlags() & SYN) == 0) {
				socket.receive(recPacket);
				netAddress = recPacket.getAddress();
				remotePort = (short)recPacket.getPort();
				byte[] tcpBytes = recPacket.getData();
				tcp.deserialize(tcpBytes);
				printPacket(false, tcp);
				if ((tcp.getFlags() & SYN) != 0) {
					ack = tcp.getSequence() + 1;
					tcp = new TCP(0, ack, null, SYN | ACK);
					sendPacket(socket, netAddress, tcp);
					tcp = receivePacket(socket, recPacket);
					if ((tcp.getFlags() & ACK) != 0) {
						sequence = 1;
						break;
					}
				}
			}
			// Connection established, read data
			while (true) {
				tcp = receivePacket(socket, recPacket);
				if ((tcp.getFlags() ^ FIN) == 0) {
					ack++;
					break;
				}
				byte[] data = tcp.getData();
				if (tcp.getSequence() <= ack + mtu) {
					outputStream.write(data);
					ack += data.length;
					tcp = new TCP(sequence, ack, null, ACK);
					sendPacket(socket, netAddress, tcp); // Send ACK
				} else {
					// Discarding out-of-order packet
					tcp = new TCP(sequence, ack, null, ACK);
					sendPacket(socket, netAddress, tcp);
				}
			}
			// FIN
			sequence++;
			tcp = new TCP(sequence, ack, null, ACK | FIN);
			sendPacket(socket, netAddress, tcp);
			tcp = receivePacket(socket, recPacket);
			outputStream.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	static TCP receivePacket(DatagramSocket socket, DatagramPacket recPacket) throws Exception {
		socket.receive(recPacket);
		byte[] tcpBytes = recPacket.getData();
		TCP recTCP = new TCP(0, 0, null, 0);
		recTCP.deserialize(tcpBytes);
		printPacket(false, recTCP);
		return recTCP;
		
	}

	static void sendPacket(DatagramSocket socket, InetAddress address, TCP tcp) throws Exception {
		TimeUnit.SECONDS.sleep(1);
		byte[] serial = tcp.serialize();
		DatagramPacket TCPpacket = new DatagramPacket(serial, serial.length, address, remotePort);
		socket.send(TCPpacket);
		printPacket(true, tcp);
	}

	static void printPacket(Boolean send, TCP tcp) {
		String sndOrRcv = send ? "snd" : "rcv";
		double time = (double)(System.currentTimeMillis() - startTime) / 1000.0;
		short flags = tcp.getFlags();
		int length = tcp.getLength();
		String ack = ((flags & ACK) != 0) ? "A" : "-";
		String fin = ((flags & FIN) != 0) ? "F" : "-";
		String syn = ((flags & SYN) != 0) ? "S" : "-";
		String data = (length > 0) ? "D" : "-";
		System.out.printf("%s %.3f %s %s %s %s %d %d %d\n",
			sndOrRcv, time, syn, ack, fin, data, tcp.getSequence(), length, tcp.getAcknowledge());

	}

    static void usage()
	{
		System.out.println("java TCPend -p <port> -s <remote IP> -a <remote port> f <file name> -m <mtu> -c <sws>");
		System.out.println("java TCPend -p <port> -m <mtu> -c <sws> -f <file name>");
	}
}

class TCP {
	static final int HEADER_LENGTH = 24;

	int sequence;
	int acknowledge;
	long time;
	int length;
	short flags;
	short checksum;
	byte[] data;

	TCP(int sequence, int acknowledge, byte[] data, int flags) {
		this.flags = (short)flags;
		this.sequence = sequence;
		this.acknowledge = acknowledge;
		this.data = data;
		if (this.data == null) {
			this.length = 0;
		} else {
			this.length = data.length;
		}
		this.time = System.nanoTime();
	}

	int getLength() {
		return this.length;
	}

	short getFlags() {
		return this.flags;
	}

	void setFlags(short flags) {
		this.flags = flags;
	} 

	byte[] getData() {
		return this.data;
	}

	void setData(byte[] data) {
		this.data = data;
		this.length = data.length;
	}

	int getSequence() {
		return this.sequence;
	}

	int getAcknowledge() {
		return this.acknowledge;
	}

	void deserialize(byte[] bytes) {
		ByteBuffer bb = ByteBuffer.wrap(bytes, 0, bytes.length);
		this.sequence = bb.getInt();
		this.acknowledge = bb.getInt();
		this.time = bb.getLong();
		int fourthInt = bb.getInt();
		this.length = fourthInt >> 3;
		this.flags = (short)(fourthInt & 0b111);
		this.checksum = (short)(bb.getInt() & 0b1111);
		this.data = new byte[this.length];
		System.arraycopy(bytes, HEADER_LENGTH, this.data, 0, this.length);
	}

	byte[] serialize() {
		byte[] serial = new byte[this.length + HEADER_LENGTH];
        ByteBuffer bb = ByteBuffer.wrap(serial);
		bb.putInt(this.sequence);
		bb.putInt(this.acknowledge);
		bb.putLong(this.time);
		bb.putInt((this.length << 3) | this.flags);
		bb.putShort((short)0);
		bb.putShort(this.checksum);
		if (this.data != null) {
			bb.put(this.data);
		}

		return serial;
	}

	
}
