import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.lang.Math;

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
	private static double ERTT = 0.0;
	private static double EDEV = 0.0;
	static long timeout = 5000;
	static Timer timer = new Timer();
	static DatagramSocket socket;
	static InetAddress netAddress;

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
		timer.cancel();
		timer.purge();
    }

	static void sender() {
		TCP[] segArray = new TCP[0];
		try {
			FileInputStream stream = new FileInputStream(file);
			byte[] fileBuf = stream.readAllBytes();
			int numSegs = 1 + (fileBuf.length / TCPdataSize);
			segArray = new TCP[numSegs];
			int curSeg = 0;
			byte dataBuf[] = new byte[TCPdataSize];
			int dataBufLength = TCPdataSize;
			int start = 0;
			while (curSeg < numSegs - 1) {
				start = curSeg * TCPdataSize;
				dataBuf = Arrays.copyOfRange(fileBuf, start, start + dataBufLength);
				TCP tcp = new TCP(1 + (curSeg * TCPdataSize), 1, dataBuf, 0);
				segArray[curSeg] = tcp;
				curSeg++;
			}
			start = curSeg * TCPdataSize;
			if (fileBuf.length % TCPdataSize != 0) {
				dataBuf = Arrays.copyOfRange(fileBuf, start, start + (fileBuf.length % TCPdataSize));
			} else {
				dataBuf = Arrays.copyOfRange(fileBuf, start, start + dataBufLength);
			}
			TCP tcp = new TCP(1 + (curSeg * TCPdataSize), 1, dataBuf, 0);
			segArray[curSeg] = tcp;
			stream.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			socket = new DatagramSocket(myPort);
			netAddress = InetAddress.getByName(remoteIP);
			byte[] datagramBytes = new byte[mtu];
			DatagramPacket packet = new DatagramPacket(datagramBytes, mtu);
			TCP tcp = new TCP( 0, 0, null,SYN);
			// int retransmits = 0;
			int sequence = 0;
			// int ack = 0;
			// int recAck = 0;

			// Create connection
			socket.setSoTimeout(2000);

			while (true) {
				sendPacket(socket, netAddress, tcp);
				try {
					TCP synacktcp = receivePacket(socket, packet);
					if ((synacktcp.getFlags() ^ (SYN | ACK)) == 0) {
						// retransmits = 0;
						sequence = 1;
						// ack = 1;
						break;
					}
				} catch (SocketTimeoutException e) {
					// retransmits++;
					// if (retransmits >= 15) {
					// 	System.err.println("Too many retransmits, quitting");
					// 	return;
					// }
				}
			}
			tcp = new TCP(sequence, 1, null, ACK);
			// recAck = 1;
			sendPacket(socket, netAddress, tcp);
			// int finalAck = segArray[segArray.length - 1].sequence;
			socket.setSoTimeout(50);
			int ackedSegs = 0;
			while (ackedSegs < segArray.length) { // Send file data
				// if (retransmits == 0) {
					
				// } else if (retransmits >= 16) {
				// 	System.err.println("Too many retransmits, quitting");
				// 	return;
				// }

				while ((segsInFlight < sws) && (ackedSegs + segsInFlight < segArray.length)) {
					// dataBuf = stream.readNBytes(mtu - TCP.HEADER_LENGTH);
					// if (dataBuf.length < (mtu - TCP.HEADER_LENGTH)) {
					// 	mtu = (short)(dataBuf.length + TCP.HEADER_LENGTH); // Dirty
					// }
					segArray[ackedSegs + segsInFlight].schedule();
					// sendPacket(socket, netAddress, segArray[ackedSegs + segsInFlight]);
					segsInFlight++;
				}
				

				try { // Wait for ACK
					tcp = receivePacket(socket, packet);
					int segsAcked = (tcp.getAcknowledge() - 1 - (ackedSegs * TCPdataSize)) / TCPdataSize;
					if ((tcp.getAcknowledge() - 1) % TCPdataSize != 0) {
						segsAcked++;
					}
					
					if (segsAcked < 1) {
						TCP ackedSeg = segArray[ackedSegs + segsAcked - 1];
						if (ackedSeg.acked) {
							System.out.println("Double ACK");
							if (ackedSeg.doubleAcked) {
								TCP nextSeg = segArray[ackedSegs + segsAcked];
								System.out.println("FastRetrans");
								nextSeg.schedule();
								segsInFlight--;
							} else {
								ackedSeg.doubleAcked = true;
							}
						} else {
							System.out.println("How did we get here?");
						}
					} else {
						for (int i = ackedSegs; i < (ackedSegs + segsAcked); i++) {
							segArray[i].acked = true;
							segArray[i].transmits = 0;
						}
						ackedSegs += segsAcked;
						segsInFlight -= segsAcked;
					}
					
					

					// recAck = tcp.getAcknowledge();
					// retransmits = 0;
				} catch (SocketTimeoutException e) { // If no ACK
					// System.out.println("No ACK");
				// 	// retransmits++;
				}

			}

			// Close connection
			socket.setSoTimeout(1000);
			while (true) {
				tcp = new TCP(sequence, 1, null, FIN);
				sendPacket(socket, netAddress, tcp);
				tcp = receivePacket(socket, packet);
				if ((tcp.getFlags() ^ (FIN | ACK)) == 0) {
					// ack++;
					tcp = new TCP(sequence, 2, null, ACK);
					sendPacket(socket, netAddress, tcp);
					break;
				}
			}
			

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
				if (tcp.checksum == tcp.calulateCheckSum()) {
					if (tcp.getSequence() == ack) {
						outputStream.write(data);
						ack += data.length;
					} else {
						// TODO Check if received ahead of time, can we buffer?
						System.out.println("OOO");
					}
				} else {
					System.out.println("Bad checksum");
					tcp.calulateCheckSum();
				}
				long time = tcp.time;
				tcp = new TCP(sequence, ack, null, ACK);
				tcp.time = time;
				
				sendPacket(socket, netAddress, tcp); // Send ACK
			}
			// FIN
			sequence++;
			tcp = new TCP(sequence, ack, null, ACK | FIN);
			sendPacket(socket, netAddress, tcp);
			tcp = receivePacket(socket, recPacket);
			socket.close();
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
		if (isSender && (recTCP.getFlags() & ACK) == ACK) {
			if (recTCP.getSequence() == 0) {
				ERTT = System.nanoTime() - recTCP.time;
				EDEV = 0.0;
				timeout = (long)(2.0 * ERTT / 1000000.0);
			} else {
				double SRTT = System.nanoTime() - recTCP.time;
				double SDEV = Math.abs(SRTT - ERTT);
				ERTT = 0.875 * ERTT + (1 - 0.875) * SRTT;
				EDEV = 0.75 * EDEV + (1 - 0.75) * SDEV;
				timeout = (long)(ERTT + 4 * EDEV);
			}
		}
		return recTCP;
		
	}

	static void sendPacket(DatagramSocket socket, InetAddress address, TCP tcp) throws IOException, InterruptedException {
		tcp.time = System.nanoTime();
		byte[] serial = tcp.serialize();
		DatagramPacket TCPpacket = new DatagramPacket(serial, serial.length, address, remotePort);
		socket.send(TCPpacket);
		printPacket(true, tcp);
		TimeUnit.MILLISECONDS.sleep(10);
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
	short checksum = 0;
	byte[] data;
	boolean acked = false;
	boolean doubleAcked = false;
	int transmits = 0;

	TCP(int sequence, int acknowledge, byte[] data, int flags) {
		this.flags = (short)flags;
		this.sequence = sequence;
		this.acknowledge = acknowledge;
		this.data = data;
		if (this.data == null) {
			this.length = 0;
		} else {
			this.length = data.length;
			this.checksum = calulateCheckSum();
		}
	}

	short calulateCheckSum() {
		if (this.length == 0) {
			return 0;
		}
		int temp = this.data[0] << 8 | this.data[1];
		for (int i = 2; i < this.data.length - 1; i += 2) {
			temp += (data[i] << 8 | data[i + 1]);
			if ((temp & 0xFFFF0000) > 1) {
				temp++;
				temp &= 0xFFFF;
			}
		}
		if (this.data.length % 2 == 1) {
			temp += (this.data[this.data.length - 1] << 8);
			if ((temp & 0xFFFF0000) > 1) {
				temp++;
				temp &= 0xFFFF;
			}
		}
		return (short)(temp ^ 0xFFFF);
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
		this.checksum = (short)(bb.getInt() & 0xFFFF);
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

	transmitTask task = new transmitTask();

	class transmitTask extends TimerTask {
		public void run() {
			if (!TCP.this.acked) {
				TCP.this.transmits++;
				if (TCP.this.transmits > 16) {
					System.out.println("Too many retransmits, exiting");
					System.exit(0);
				} else if (TCP.this.transmits > 1) {
					System.out.println("Timeout reached, retransmitting");
				}
				try {
					TCPend.sendPacket(TCPend.socket, TCPend.netAddress, TCP.this);
				} catch (IOException e) {
					
				} catch (InterruptedException e) {
	
				}
				TCPend.timer.schedule(new transmitTask(), TCPend.timeout);
			} else {
				// this.cancel();
			}
		}
	}

	void schedule() {

		task.run();
	}
}