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
import java.util.Timer;
import java.util.TimerTask;
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
	private static double ERTT = 0.0;
	private static double EDEV = 0.0;
	static long timeout = 5000;
	static Timer timer = new Timer();
	static DatagramSocket socket;
	static InetAddress netAddress;
	static int retransmissions = 0;
	static int dupAcks = 0;
	static int wrongChecksum = 0;
	static int OOOSegs = 0;
	static int packetsReceived = 0;

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
		if (mtu < 24) {
			System.out.println("MTU must be at least 24");
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
			int numSegs = fileBuf.length / TCPdataSize;
			if ((fileBuf.length % TCPdataSize) > 0) {numSegs++;};
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
		TCP tcp = new TCP( 0, 0, null,SYN);
		try {
			socket = new DatagramSocket(myPort);
			netAddress = InetAddress.getByName(remoteIP);
			byte[] datagramBytes = new byte[mtu];
			DatagramPacket packet = new DatagramPacket(datagramBytes, mtu);
			tcp.time = System.nanoTime();
			int retransmits = 0;
			// int ack = 0;
			// int recAck = 0;

			// Create connection
			socket.setSoTimeout(500);

			while (true) {
				sendPacket(socket, netAddress, tcp);
				try {
					TCP synacktcp = receivePacket(socket, packet);
					if ((synacktcp.getFlags() ^ (SYN | ACK)) == 0) {
						// retransmits = 0;
						// ack = 1;
						break;
					}
				} catch (SocketTimeoutException e) {
					tcp.time = System.nanoTime();
					retransmits++;
					if (retransmits >= 15) {
						// System.err.println("Too many retransmits, quitting");
						return;
					}
				}
			}
			tcp = new TCP(1, 1, null, ACK);
			// recAck = 1;
			sendPacket(socket, netAddress, tcp);
			// int finalAck = segArray[segArray.length - 1].sequence;
			socket.setSoTimeout(1);
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
						dupAcks++;
						TCP ackedSeg;
						if (ackedSegs + segsAcked < 1) {
							ackedSeg = segArray[0];
						} else {
							ackedSeg = segArray[ackedSegs + segsAcked - 1];
						}
						if (ackedSeg.acked) {
							dupAcks++;
							// System.out.println("Double ACK");
							if (ackedSeg.doubleAcked) {
								TCP nextSeg = segArray[ackedSegs + segsAcked];
								// System.out.println("FastRetrans");
								retransmits++;
								sendPacket(socket, netAddress, nextSeg);
								// segsInFlight--;
								ackedSeg.doubleAcked = false;
							} else {
								ackedSeg.doubleAcked = true;
							}
						} else {
							// System.out.println("How did we get here?");
						}
					} else {
						if (segsAcked > 1) {
							// System.out.println("Cum ACK");
						}
						for (int i = ackedSegs; i < (ackedSegs + segsAcked); i++) {
							segArray[i].task.cancel();
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
			int seq = tcp.getAcknowledge();
			TCP fintcp = new TCP(seq, 1, null, FIN);
			fintcp.schedule();
			while (true) {
				try {
					tcp = receivePacket(socket, packet);
				} catch (SocketTimeoutException e) {
				}
				
				if ((tcp.getFlags() ^ (FIN | ACK)) == 0) {
					fintcp.acked = true;
					tcp = new TCP(seq + 1, 2, null, ACK);
					sendPacket(socket, netAddress, tcp);
					break;
				}
			}

			socket.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.out.println("Bytes sent: " + (tcp.getSequence() - 2));
		System.out.println("Packets sent: " + segArray.length);
		System.out.println("Retransmissions: " + retransmissions);
		// System.out.println("Out of order packets discarded: " + OOOSegs);
		// System.out.println("Incorrect checksum packets: " + wrongChecksum);
		// System.out.println("Duplicate Acks: " + dupAcks);
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
			int debugCounter = 0;

			// Wait for SYN
			while (true) {
				socket.receive(recPacket);
				netAddress = recPacket.getAddress();
				remotePort = (short)recPacket.getPort();
				byte[] tcpBytes = recPacket.getData();
				tcp.deserialize(tcpBytes);
				printPacket(false, tcp);
				if ((tcp.getFlags() ^ SYN) == 0) {
					// System.out.println("Syn got");
					socket.setSoTimeout(5000);
					ack = 1;
					long time = tcp.time;
					tcp = new TCP(0, ack, null, SYN | ACK);
					tcp.time = time;
					sendPacket(socket, netAddress, tcp);
					while (true) {
						try {
							tcp = receivePacket(socket, recPacket);
							if ((tcp.getFlags() ^ SYN) == 0) {
								// System.out.println("Got another SYN");
								time = tcp.time;
								tcp = new TCP(0, ack, null, SYN | ACK);
								tcp.time = time;
								sendPacket(socket, netAddress, tcp);
								continue;
							}
							sequence = 1;
							break;
						} catch (SocketTimeoutException e) {
							sendPacket(socket, netAddress, tcp);
						}
					}
					if (sequence == 1) {
						break;
					}
				}
			}
			// Connection established, read data
			TCP[] outOfOrderSegs = new TCP[sws - 1];
			int numOutOfOrderSegs = 0;
			boolean skipFirst = false;
			if ((tcp.getFlags() ^ ACK) != 0) {
				// System.out.println("Ack missed");
				skipFirst = true;
			}
			while (true) {
				if (!skipFirst) {
					tcp = receivePacket(socket, recPacket);
				} else {
					skipFirst = false;
				}
				if ((tcp.getFlags() ^ FIN) == 0) {
					ack++;
					break;
				}
				// if (debugCounter++ % 4 == 3) {
				// 	continue;
				// }
				byte[] data = tcp.getData();
				// if (ack > 20 && debugCounter == 0) {
				// 	debugCounter++;
				// 	continue;
				// }
				if (tcp.checksum == tcp.calulateCheckSum()) {
					if (tcp.getSequence() == ack) {
						outputStream.write(data);
						ack += data.length;
						packetsReceived++;
						while (numOutOfOrderSegs > 0) {
							int i = 0;
							for (i = 0; i < outOfOrderSegs.length; i++) {
								if (outOfOrderSegs[i] != null && outOfOrderSegs[i].sequence == ack) {
									data = outOfOrderSegs[i].getData();
									outputStream.write(data);
									packetsReceived++;
									ack += data.length;
									numOutOfOrderSegs--;
									// System.out.println("Got seg from buffer, seq" + outOfOrderSegs[i].sequence);
									outOfOrderSegs[i] = null;
									break;
								}
							}
							if (i == outOfOrderSegs.length) {
								// System.out.println("Whatever this means");
								break;
							}
						}
					} else {
						dupAcks++;
						// System.out.println("OOO");
						int receivedSeq = tcp.getSequence();
						if (receivedSeq > ack) {
							// System.out.println("Checking buffer");
							int spot = -1;
							for (int i = 0; i < outOfOrderSegs.length; i++) {
								if (outOfOrderSegs[i] == null) {
									spot = i; 
									continue;
								} else {
									if (outOfOrderSegs[i].sequence == receivedSeq) {
										spot = -1;
										break; // Already buffered
									}
								}
							}
							// Add to buffer
							if (spot != -1) {
								// System.out.println("Buffering seg");
								numOutOfOrderSegs++;
								outOfOrderSegs[spot] = tcp;
							}
						} else {
							dupAcks++;
							OOOSegs++;
						}
					}
				} else {
					dupAcks++;
					wrongChecksum++;
					// System.out.println("Bad checksum");
				}
				long time = tcp.time;
				tcp = new TCP(1, ack, null, ACK);
				tcp.time = time;
				
				sendPacket(socket, netAddress, tcp); // Send ACK
			}
			// FIN
			long time = tcp.time;
			tcp = new TCP(1, ack, null, ACK | FIN);
			tcp.time = time;
			sendPacket(socket, netAddress, tcp);
			socket.setSoTimeout(200);
			int transmits = 1;
			while (transmits < 16) {
				try {
					tcp = receivePacket(socket, recPacket);
					if ((tcp.getFlags() ^ ACK) == 0) {
						break;
					} else {
						time = tcp.time;
						tcp = new TCP(1, ack, null, ACK | FIN);
						tcp.time = time;
					}
				} catch (SocketTimeoutException e) {
					transmits++;
					sendPacket(socket, netAddress, tcp);
				}
			}
			
			socket.close();
			outputStream.close();
			System.out.println("Bytes received: " + (ack - 2));
			System.out.println("Packets Received: " + packetsReceived);
			System.out.println("Out of order packets discarded: " + OOOSegs);
			System.out.println("Incorrect checksum packets: " + wrongChecksum);
			System.out.println("Duplicate Acks: " + dupAcks);
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
		if (isSender && recTCP.getSequence() == 0) {
			ERTT = System.nanoTime() - recTCP.time;
			EDEV = 0.0;
			timeout = (long)(2.0 * ERTT / 1000000.0);
			// System.out.printf("%d %f %f\n",timeout,ERTT,EDEV);
		} else if (isSender) {
			double SRTT = System.nanoTime() - recTCP.time;
			double SDEV = Math.abs(SRTT - ERTT);
			ERTT = 0.875 * ERTT + (1 - 0.875) * SRTT;
			EDEV = 0.75 * EDEV + (1 - 0.75) * SDEV;
			timeout = (long)((ERTT + 4 * EDEV) / 1000000.0);
			// System.out.printf("%d %f %f %f %f\n",timeout,SRTT,SDEV,ERTT,EDEV);
			// System.out.println("TO, SRTT, SDEV, ERTT, EDEV " + timeout + SRTT + SDEV + ERTT + EDEV);
		}
		return recTCP;
		
	}

	static void sendPacket(DatagramSocket socket, InetAddress address, TCP tcp) throws IOException, InterruptedException {
		if (isSender) {
			tcp.time = System.nanoTime();
		}
		byte[] serial = tcp.serialize();
		DatagramPacket TCPpacket = new DatagramPacket(serial, serial.length, address, remotePort);
		socket.send(TCPpacket);
		printPacket(true, tcp);
		// TimeUnit.MILLISECONDS.sleep(10);
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
		int fifthInt = bb.getInt();
		this.length = fifthInt >> 3;
		this.flags = (short)(fifthInt & 0b111);
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
					// System.out.println("Too many retransmits, exiting");
					System.exit(0);
				} else if (TCP.this.transmits > 1) {
					// System.out.println("Timeout reached, retransmitting");
					TCPend.retransmissions++;
				}
				try {
					TCPend.sendPacket(TCPend.socket, TCPend.netAddress, TCP.this);
				} catch (IOException e) {
					
				} catch (InterruptedException e) {
	
				}
				// System.out.println("Time now " + System.nanoTime());
				// System.out.println("Timeout here " + TCPend.timeout);
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