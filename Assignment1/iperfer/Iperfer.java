import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;

class Iperfer {
    public static void main(String[] args) {
        Boolean isClient = null;
        String host = null;
        int port = -1;
        int time = -1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-c":
                    if (isClient == null && args.length == 7) {
                        isClient = true;
                    } else {
                        System.out.println("Error: missing or additional arguments");
                        return;
                    }
                    break;
                case "-h":
                    if (host == null && i < args.length) {
                        try {
                            host = args[++i];
                        } catch (Exception e) {
                            System.out.println("Error: missing or additional arguments");
                            return;
                        }
                    }
                    break;
                case "-p":
                    if (port == -1 && i < args.length) {
                        try {
                            port = Integer.parseInt(args[++i]);
                        } catch (Exception e) {
                            System.out.println("Error: missing or additional arguments");
                            return;
                        }
                        if (port < 1024 || port > 65535) {
                            System.out.println("Error: port number must be in the range 1024 to 65535");
                            return;
                        }
                    } else {
                        System.out.println("Error: missing or additional arguments");
                        return;
                    }
                    break;
                case "-t":
                    if (time == -1 && i < args.length) {
                        try {
                            time = Integer.parseInt(args[++i]);
                        } catch (Exception e) {
                            System.out.println("Error: missing or additional arguments");
                            return;
                        }
                    } else {
                        System.out.println("Error: missing or additional arguments");
                        return;
                    }
                    break;
                case "-s":
                    if (isClient == null && args.length == 3) {
                        isClient = false;
                    } else {
                        System.out.println("Error: missing or additional arguments");
                        return;
                    }
                    break;
                default:
                    System.out.println("Error: missing or additional arguments");
                    return;
            }
        }
        if (isClient) {
            try {
                long kB = 0;
                Socket socket = new Socket(host, port);
                byte[] data = new byte[1000];
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                long milliseconds = 0, start = 0, end = 0;
                while (milliseconds < (time * 1000)) {
                    start = System.currentTimeMillis();
                    output.write(data);
                    end = System.currentTimeMillis();
                    milliseconds += end - start;
                    kB++;
                }
                output.close();
                socket.close();
                double rate = ((8.0 * (double) kB) / (double) milliseconds);
                System.out.println("sent=" + kB + " KB rate=" + rate + " Mbps");
            } catch (Exception e) {

            }
        } else {
            try {
                ServerSocket server = new ServerSocket(port);
                Socket client = server.accept();
                DataInputStream in = new DataInputStream(client.getInputStream());
                byte[] data = new byte[1000];
                long totalBytes = 0;
                long milliseconds = 0, start = 0, end = 0;
                while (true) {
                    start = System.currentTimeMillis();
                    int bytes = in.read(data, 0, 1000);
                    end = System.currentTimeMillis();
                    if (bytes == -1) {
                        break;
                    }
                    milliseconds += end - start;
                    totalBytes += bytes;
                }
                in.close();
                client.close();
                server.close();
                double rate = ((8.0 * (double) totalBytes / 1000) / (double) milliseconds);
                System.out.println("received=" + (totalBytes / 1000) + " KB rate=" + rate + " Mbps");
            } catch (Exception e) {

            }
        }
    }
}