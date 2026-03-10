CNT4007 Project
---------------
Current Progress
-----------------
The following components have been implemented:

• Peer startup and configuration loading    
• Server socket creation for incoming peer connections   
• Client connections to previously started peers     
• Handshake message exchange     
• Bitfield exchange between peers    
• Piece request and transfer protocol     
• File piece storage and reconstruction    
• HAVE message broadcasting when pieces are obtained      
• Interested / Not Interested message handling     
• Choking and unchoking logic     
• Optimistic unchoking      
• Preferred neighbor selection     
• Logging of peer events according to project specification     
• Detection of file completion      


Compilation
-----------     
From Project directory root run:
javac -d out src/*.java

Running the Peers
-----------------
In different terminals run:   

java -cp out peerProcess 1001    
java -cp out peerProcess 1002    
java -cp out peerProcess 1003
