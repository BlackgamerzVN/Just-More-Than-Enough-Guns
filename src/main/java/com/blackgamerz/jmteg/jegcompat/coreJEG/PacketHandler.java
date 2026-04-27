package com.blackgamerz.jmteg.jegcompat.coreJEG;

public class PacketHandler {
    public static Channel getPlayChannel() { return new Channel(); }
    public static class Channel {
        public void sendToNearbyPlayers(Runnable posSupplier, Object msg) {}
    }
}