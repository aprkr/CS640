default: TCPend.class

rec: TCPend.class
	java TCPend -p 1234 -m 28 -c 1 f tcpreceive.txt

send: TCPend.class
	java TCPend -p 4567 -s localhost -a 1234 f ../http_server/index.html -m 28 -c 1

mininetsend: TCPend.class
	java TCPend -p 4567 -s 10.0.1.101 -a 1234 f ../http_server/index.html -m 28 -c 1

TCPend.class: TCPend.java
	javac TCPend.java

