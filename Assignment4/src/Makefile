mtu = 100
sws = 100
# sourceFile = tcpreceive.txt
# destinationFile = tcpreceive2.txt
sourceFile = ../http_server/index.html
destinationFile = tcpreceive.txt
receivePort = 1234
sendPort = 4567

default: TCPend.class

rec: TCPend.class
	java TCPend -p $(receivePort) -m $(mtu) -c $(sws) f $(destinationFile)

send: TCPend.class
	java TCPend -p $(sendPort) -s localhost -a $(receivePort) f $(sourceFile) -m $(mtu) -c $(sws)

mininetsend: TCPend.class
	java TCPend -p $(sendPort) -s 10.0.1.101 -a $(receivePort) f $(sourceFile) -m $(mtu) -c $(sws)

TCPend.class: TCPend.java
	javac TCPend.java

