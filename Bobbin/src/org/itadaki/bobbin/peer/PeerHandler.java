/*
 * Copyright (c) 2010 Matthew J. Francis and Contributors of the Bobbin Project
 * This file is distributed under the MIT licence. See the LICENCE file for further information.
 */
package org.itadaki.bobbin.peer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.itadaki.bobbin.bencode.BDictionary;
import org.itadaki.bobbin.connectionmanager.Connection;
import org.itadaki.bobbin.connectionmanager.ConnectionManager;
import org.itadaki.bobbin.connectionmanager.ConnectionReadyListener;
import org.itadaki.bobbin.peer.protocol.PeerProtocolConstants;
import org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer;
import org.itadaki.bobbin.peer.protocol.PeerProtocolParser;
import org.itadaki.bobbin.torrentdb.BlockDescriptor;
import org.itadaki.bobbin.torrentdb.PieceDatabase;
import org.itadaki.bobbin.torrentdb.PieceStyle;
import org.itadaki.bobbin.torrentdb.ResourceType;
import org.itadaki.bobbin.torrentdb.StorageDescriptor;
import org.itadaki.bobbin.torrentdb.ViewSignature;
import org.itadaki.bobbin.util.BitField;
import org.itadaki.bobbin.util.elastictree.HashChain;


/**
 * A {@code PeerHandler} controls the interaction between the local peer and a single remote peer,
 * standing between a {@link ConnectionManager} which controls the underlying data connection to the
 * remote peer, and a {@link PeerCoordinator} (through the {@link PeerServices} interface) which
 * coordinates request management and peer choking globally across a torrent.
 *
 * <p>When the {@link ConnectionManager} is ready to send or receive bytes across the connection to
 * a remote peer, it causes the {@link Connection} that is associated with the peer's
 * {@link PeerHandler} to signal that readiness on its {@link ConnectionReadyListener} interface.
 * The {@link PeerHandler} then reads and writes as many bytes as it can through the
 * {@link Connection}.
 *
 * <p>Bytes that are read are processed through a {@link PeerProtocolParser}, which in turn calls
 * one of the {@link PeerProtocolConsumer} methods on the {@code PeerHandler} as soon as a either a
 * complete message is received, or a protocol error in the input data is detected.
 *
 * <p>In response to completely received messages, the {@link PeerHandler} will update its internal
 * representation of the remote peer's state, delegate any actions with torrent global impact to its
 * {@link PeerCoordinator}, and inform its {@link PeerOutboundQueue} of any messages that should be
 * queued for sending to the remote peer.
 *
 * <p>Finally, the {@link PeerOutboundQueue} writes as many bytes as possible to the remote peer
 * through the {@link Connection}, keeping internal track of partly sent and unsent messages. Note
 * that the queue may order some messages in front of others, and that not all messages that are
 * requested may actually be sent; some message types (interested / not interested, request /
 * cancel) may instead, if their respective opposite message type is waiting unsent, cancel that
 * unsent message from the queue.
 *
 * <p>Actions that are delegated to a {@link PeerCoordinator} include the allocation of piece
 * requests to the remote peer, the handling of received piece block data, and the invocation of the
 * choking algorithm when required.
 */
public class PeerHandler implements ManageablePeer, PeerProtocolConsumer, ConnectionReadyListener {

	/**
	 * The connection to the remote peer
	 */
	private final Connection connection;

	/**
	 * The PeerServices for the torrent that we are talking to the remote peer about
	 */
	private PeerServices peerServices;

	/**
	 * The parser used to process incoming data
	 */
	private final PeerProtocolParser protocolParser;

	/**
	 * The queue used to process outgoing data
	 */
	private PeerOutboundQueue outboundQueue;

	/**
	 * The torrent's PieceDatabase
	 */
	private PieceDatabase pieceDatabase;

	/**
	 * Protocol statistics about the peer
	 */
	private final PeerStatistics peerStatistics;

	/**
	 * The peer protocol state
	 */
	private PeerState state = new PeerState();


	/* Peer interface */

	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getRemoteAddress()
	 */
	public InetSocketAddress getRemoteSocketAddress() {

		return this.connection.getRemoteSocketAddress();

	}

	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getPeerState()
	 */
	public PeerState getPeerState() {

		return this.state;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.Peer#getStatistics()
	 */
	public ReadablePeerStatistics getReadableStatistics() {

		return this.peerStatistics;

	}

	/* ManageablePeer interface */

	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#getStatistics()
	 */
	public PeerStatistics getStatistics() {

		return this.peerStatistics;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#getBitField()
	 */
	public BitField getRemoteBitField() {

		return this.state.remoteBitField;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#getTheyHaveOutstandingRequests()
	 */
	public boolean getTheyHaveOutstandingRequests() {

		return (this.outboundQueue.getUnsentPieceCount () > 0);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#setWeAreChoking(boolean)
	 */
	public boolean setWeAreChoking (boolean weAreChokingThem) {

		if (weAreChokingThem != this.state.weAreChoking) {
			this.state.weAreChoking = weAreChokingThem;
			// Any unsent block requests from the remote peer, except Allowed Fast requests, will be
			// discarded
			List<BlockDescriptor> descriptors = this.outboundQueue.sendChokeMessage (this.state.weAreChoking);
			if (this.state.fastExtensionEnabled) {
				// Explicitly discard requests
				this.outboundQueue.sendRejectRequestMessages (descriptors);
			}
			return true;
		}

		return false;

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#setWeAreInterested(boolean)
	 */
	public void setWeAreInterested (boolean weAreInterested) {

		// Set our interest and inform the remote peer if needed
		if (weAreInterested != this.state.weAreInterested) {
			this.state.weAreInterested = weAreInterested;
			this.outboundQueue.sendInterestedMessage (weAreInterested);
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#cancelRequests(java.util.List)
	 */
	public void cancelRequests (List<BlockDescriptor> requestsToCancel) {

		// Cancel the passed requests. If the Fast extension is enabled, indicate to the outbound
		// queue that it should continue to track the cancelled request so that it later matches
		// the remote peer's response (piece or reject)
		for (BlockDescriptor request : requestsToCancel) {
			this.outboundQueue.sendCancelMessage (request, this.state.fastExtensionEnabled);
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#rejectPiece(int)
	 */
	public void rejectPiece (int pieceNumber) {

		this.outboundQueue.rejectPieceMessages (pieceNumber);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#sendHavePiece(int)
	 */
	public void sendHavePiece (int pieceNumber) {

		this.outboundQueue.sendHaveMessage (pieceNumber);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#sendKeepalive()
	 */
	public void sendKeepaliveOrClose() {

		if ((System.currentTimeMillis() - this.state.lastDataReceivedTime) > (PeerProtocolConstants.IDLE_INTERVAL * 1000)) {
			close();
		} else {
			this.outboundQueue.sendKeepaliveMessage();
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#sendViewSignature(org.itadaki.bobbin.peer.ViewSignature)
	 */
	public void sendViewSignature (ViewSignature viewSignature) {

		this.outboundQueue.sendElasticSignatureMessage (viewSignature);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ExtensiblePeer#sendExtensionHandshake(java.util.Set, java.util.Set, org.itadaki.bobbin.bencode.BDictionary)
	 */
	public void sendExtensionHandshake (Set<String> extensionsAdded, Set<String> extensionsRemoved, BDictionary extra) {

		this.outboundQueue.sendExtensionHandshake (extensionsAdded, extensionsRemoved, extra);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ExtensiblePeer#sendExtensionMessage(java.lang.String, java.nio.ByteBuffer)
	 */
	public void sendExtensionMessage (String identifier, ByteBuffer data) {

		this.outboundQueue.sendExtensionMessage (identifier, data);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.ManageablePeer#close()
	 */
	public void close() {

		try {
			this.connection.close();
		} catch (IOException e) {
			// Shouldn't happen, and nothing we can do if it does
		}
		if (this.peerServices != null) {
			this.peerServices.peerDisconnected (this);
		}

	}


	/* PeerProtocolConsumer interface */

	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.DefaultPeerProtocolConsumer#keepAliveMessage()
	 */
	@Override
	public void keepAliveMessage() {
		// Do nothing
		// The time of the last received data, which is implicitly updated by the receipt of a
		// keepalive, is consumed in #sendKeepaliveOrClose()
	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.DefaultPeerProtocolConsumer#chokeMessage(boolean)
	 */
	@Override
	public void chokeMessage (boolean choked) {

		this.state.theyAreChoking = choked;

		this.outboundQueue.setRequestsPlugged (choked);
		if (this.state.theyAreChoking && !this.state.fastExtensionEnabled) {
			this.outboundQueue.requeueAllRequestMessages();
		}

		// We may need to send new requests. They will be added in connectionReady() when all read
		// processing is finished

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.DefaultPeerProtocolConsumer#interestedMessage(boolean)
	 */
	@Override
	public void interestedMessage (boolean interested) {

		this.state.theyAreInterested = interested;
		this.peerServices.adjustChoking (this.state.weAreChoking);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#haveMessage(org.itadaki.bobbin.torrentdb.ResourceType, int)
	 */
	@Override
	public void haveMessage (ResourceType resource, int pieceIndex) throws IOException {

		if ((pieceIndex < 0) || (pieceIndex >= this.pieceDatabase.getStorageDescriptor().getNumberOfPieces())) {
			throw new IOException ("Invalid have message");
		}

		// If we were previously not interested in the remote peer and they announce a piece we
		// need, inform them of our interest and fill the request queue
		if (!this.state.remoteBitField.get (pieceIndex)) {
			this.state.remoteBitField.set (pieceIndex);
			if (this.peerServices.addAvailablePiece (this, pieceIndex) && (!this.state.weAreInterested)) {
				this.state.weAreInterested = true;
				this.outboundQueue.sendInterestedMessage (true);
			}
		}

		if (this.state.remoteBitField.cardinality() == PeerProtocolConstants.ALLOWED_FAST_THRESHOLD) {
			this.outboundQueue.clearAllowedFastPieces();
		}

		// We may need to send new requests; they will be added in connectionReady() when all read
		// processing is finished

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#bitfieldMessage(org.itadaki.bobbin.torrentdb.ResourceType, byte[])
	 */
	@Override
	public void bitfieldMessage (ResourceType resource, byte[] bitField) throws IOException {

		// Validate the bitfield
		try {
			this.state.remoteBitField = new BitField (bitField, this.state.remoteView.getNumberOfPieces());
		} catch (IllegalArgumentException e) {
			throw new IOException (e);
		}

		// Set our interest in the remote peer
		if (this.peerServices.addAvailablePieces (this)) {
			this.state.weAreInterested = true;
			this.outboundQueue.sendInterestedMessage (true);
		}

		// Send an Allowed Fast set if appropriate 
		if (
				   this.state.fastExtensionEnabled
				&& (this.pieceDatabase.getInfo().getPieceStyle() != PieceStyle.ELASTIC)
				&& this.state.remoteBitField.cardinality() < PeerProtocolConstants.ALLOWED_FAST_THRESHOLD
		   )
		{
			generateAndSendAllowedFastSet();
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#requestMessage(org.itadaki.bobbin.torrentdb.ResourceType, org.itadaki.bobbin.torrentdb.BlockDescriptor)
	 */
	@Override
	public void requestMessage (ResourceType resource, BlockDescriptor descriptor) throws IOException {

		// Validate the descriptor
		if (!validateBlockDescriptor (descriptor)) {
			throw new IOException ("Invalid request message");
		}

		if (this.pieceDatabase.havePiece (descriptor.getPieceNumber())) {

			// Queue the request if we are not choking; If we are choking,
			//   Base protocol:  Do nothing
			//   Fast extension: Queue the request if its piece is Allowed Fast, otherwise send an
			//                   explicit reject
			if (!this.state.weAreChoking) {
				this.outboundQueue.sendPieceMessage (descriptor);
			} else if (this.state.fastExtensionEnabled) {
				if (this.outboundQueue.isPieceAllowedFast (descriptor.getPieceNumber())) {
					this.outboundQueue.sendPieceMessage (descriptor);
				} else {
					this.outboundQueue.sendRejectRequestMessage (descriptor);
				}
			}

		} else {

			// TODO Semantics - This is necessary to support the Elastic extension, but is it the best semantic?
			if (this.state.fastExtensionEnabled) {
				this.outboundQueue.sendRejectRequestMessage (descriptor);
			} else {
				throw new IOException ("Piece " + descriptor.getPieceNumber() + " not present");
			}

		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#pieceMessage(org.itadaki.bobbin.torrentdb.PieceStyle, org.itadaki.bobbin.torrentdb.ResourceType, org.itadaki.bobbin.torrentdb.BlockDescriptor, java.lang.Long, java.nio.ByteBuffer, java.nio.ByteBuffer)
	 */
	@Override
	public void pieceMessage (PieceStyle pieceStyle, ResourceType resource, BlockDescriptor descriptor, Long viewLength, ByteBuffer hashes, ByteBuffer block)
			throws IOException
	{

		// Check that the block is of the expected style
		if (this.pieceDatabase.getInfo().getPieceStyle() != pieceStyle) {
			throw new IOException ("Expected " + this.pieceDatabase.getInfo().getPieceStyle() + " piece, received " + pieceStyle);
		}

		// Validate the descriptor (PeerProtocolParser ensures that the data is of the correct length)
		if (!validateBlockDescriptor (descriptor)) {
			throw new IOException ("Invalid piece message");
		}

		// Validate the view length for an Elastic piece
		if (
				   (pieceStyle == PieceStyle.ELASTIC)
				&& (hashes != null)
				&& (!this.state.remoteViewSignatures.containsKey (viewLength))
				&& (this.pieceDatabase.getInfo().getStorageDescriptor().getLength() != viewLength)
		   )
		{
			throw new IOException ("Invalid view length in piece");
		}

		// Handle the block
		if (this.outboundQueue.requestReceived (descriptor)) {
			this.peerStatistics.blockBytesReceivedRaw.add (descriptor.getLength());
			ViewSignature viewSignature = null;
			HashChain hashChain = null;
			switch (pieceStyle) {
				case MERKLE:
					hashChain = (hashes == null) ? null : new HashChain (this.pieceDatabase.getStorageDescriptor().getLength(), hashes);
					break;
				case ELASTIC:
					viewSignature = (hashes == null) ? null : this.state.remoteViewSignatures.get (viewLength);
					hashChain = (hashes == null) ? null : new HashChain (viewLength, hashes);
					break;
			}
			this.peerServices.handleBlock (this, descriptor, viewSignature, hashChain, block);
		} else {
			if (!this.state.fastExtensionEnabled) {
				// Spam, or a request we cancelled. Can't tell the difference in the base protocol,
				// so do nothing
			} else {
				throw new IOException ("Unrequested piece received");
			}
		}

		// We may need to send new requests. They will be added in connectionReady() when all read
		// processing is finished

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#cancelMessage(org.itadaki.bobbin.torrentdb.ResourceType, org.itadaki.bobbin.torrentdb.BlockDescriptor)
	 */
	@Override
	public void cancelMessage (ResourceType resource, BlockDescriptor descriptor) throws IOException {

		// Validate the descriptor
		if (!validateBlockDescriptor (descriptor)) {
			throw new IOException ("Invalid cancel message");
		}

		// Attempt to discard the piece from the outbound queue. If the piece was removed unsent,
		//   Base protocol: Do nothing
		//   Fast extension: Send an explicit reject
		boolean removed = this.outboundQueue.discardPieceMessage (descriptor);
		if (this.state.fastExtensionEnabled && removed) {
			this.outboundQueue.sendRejectRequestMessage (descriptor);
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#suggestPieceMessage(int)
	 */
	@Override
	public void suggestPieceMessage (int pieceNumber) throws IOException {

		if ((pieceNumber < 0) || (pieceNumber >= this.pieceDatabase.getStorageDescriptor().getNumberOfPieces())) {
			throw new IOException ("Invalid suggest piece message");
		}

		// The Fast Extension spec is silent on whether it is permissible for a peer to Suggest
		// Piece a piece they don't actually have (although it would be a pretty stupid thing to
		// do). We will simply ignore any such suggestions.

		if (this.state.remoteBitField.get (pieceNumber)) {
			this.peerServices.setPieceSuggested (this, pieceNumber);
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#haveAllMessage()
	 */
	@Override
	public void haveAllMessage() {

		// The remote bitfield is initially all zero, and PeerProtocolParser ensures this message
		// can only be the first message; invert the bitfield to set all bits
		this.state.remoteBitField.not();

		// Set our interest in the remote peer.
		if (this.peerServices.addAvailablePieces (this)) {
			this.state.weAreInterested = true;
			this.outboundQueue.sendInterestedMessage (true);
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#haveNoneMessage()
	 */
	@Override
	public void haveNoneMessage() {

		// The remote bitfield is initially all zero, so there's no need to do anything to it

		// Send an Allowed Fast set
		if (this.pieceDatabase.getInfo().getPieceStyle() != PieceStyle.ELASTIC) {
			generateAndSendAllowedFastSet();
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#rejectRequestMessage(org.itadaki.bobbin.torrentdb.ResourceType, org.itadaki.bobbin.torrentdb.BlockDescriptor)
	 */
	@Override
	public void rejectRequestMessage (ResourceType resource, BlockDescriptor descriptor) throws IOException {

		if (!this.outboundQueue.rejectReceived (descriptor)) {
			throw new IOException ("Reject received for unrequested piece");
		}

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#allowedFastMessage(int)
	 */
	@Override
	public void allowedFastMessage (int pieceNumber) throws IOException {

		if ((pieceNumber < 0) || (pieceNumber >= this.state.remoteBitField.length())) {
			throw new IOException ("Invalid allowed fast message");
		}

		// The Fast Extension spec explicitly allows peers to send Allowed Fast messages for pieces
		// they don't actually have. We drop any such messages here
		if (this.state.remoteBitField.get (pieceNumber)) {
			this.peerServices.setPieceAllowedFast (this, pieceNumber);
			this.outboundQueue.setRequestAllowedFast (pieceNumber);
		}

		// We may need to send new requests. They will be added in connectionReady() when all read
		// processing is finished

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#extensionHandshakeMessage(java.util.Set, java.util.Set, org.itadaki.bobbin.bencode.BDictionary)
	 */
	@Override
	public void extensionHandshakeMessage (Set<String> extensionsEnabled, Set<String> extensionsDisabled, BDictionary extra) throws IOException
	{

		this.state.remoteExtensions.addAll (extensionsEnabled);
		this.state.remoteExtensions.removeAll (extensionsDisabled);
		this.peerServices.enableDisablePeerExtensions (this, extensionsEnabled, extensionsDisabled, extra);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#extensionMessage(java.lang.String, byte[])
	 */
	@Override
	public void extensionMessage (String identifier, byte[] data) throws IOException {

		this.peerServices.processExtensionMessage (this, identifier, data);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#elasticSignatureMessage(org.itadaki.bobbin.peer.ViewSignature)
	 */
	@Override
	public void elasticSignatureMessage (ViewSignature viewSignature) throws IOException {

		if (viewSignature.getViewLength() > this.state.remoteView.getLength()) {
			this.state.remoteView = new StorageDescriptor (this.state.remoteView.getPieceSize(), viewSignature.getViewLength());
		}

		int pieceSize = this.pieceDatabase.getStorageDescriptor().getPieceSize();
		int viewNumPieces = (int)((viewSignature.getViewLength() + pieceSize - 1) / pieceSize);
		if (viewNumPieces > this.state.remoteBitField.length()) {
			this.state.remoteBitField.extend (viewNumPieces);
		}

		if (!this.peerServices.handleViewSignature (viewSignature)) {
			throw new IOException ("Signature failed verification");
		}

		if (this.state.remoteViewSignatures.size() > 1) {
			this.state.remoteViewSignatures.pollFirstEntry();
		}
		this.state.remoteViewSignatures.put (viewSignature.getViewLength(), viewSignature);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#elasticBitfieldMessage(byte[])
	 */
	@Override
	public void elasticBitfieldMessage (byte[] bitField) throws IOException {

		// TODO Temporary - to be replaced when new Elastic Bitfield format is decided
		bitfieldMessage (null, bitField);

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#resourceDirectoryMessage(java.util.List, java.util.List)
	 */
	@Override
	public void resourceDirectoryMessage (List<ResourceType> resources, List<Integer> lengths) throws IOException {

		// TODO implementation

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.protocol.PeerProtocolConsumer#resourceSubscribeMessage(org.itadaki.bobbin.torrentdb.ResourceType)
	 */
	@Override
	public void resourceSubscribeMessage (ResourceType resource) throws IOException {

		// TODO implementation

	}


	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.peer.DefaultPeerProtocolConsumer#unknownMessage(int, byte[])
	 */
	@Override
	public void unknownMessage (int messageID, byte[] messageBytes) {

		// Ignore it

	}


	/* ConnectionReadyListener interface */

	/* (non-Javadoc)
	 * @see org.itadaki.bobbin.connectionmanager.ConnectionReadyListener#connectionReady(org.itadaki.bobbin.connectionmanager.Connection, boolean, boolean)
	 */
	public void connectionReady (Connection connection, boolean readable, boolean writeable) {

		if (this.peerServices != null) {
			this.peerServices.lock();
		}

		try {

			if (readable) {
				int bytesRead = this.protocolParser.parseBytes (connection);
				this.peerStatistics.protocolBytesReceived.add (bytesRead);
				if (bytesRead > 0) {
					this.state.lastDataReceivedTime = System.currentTimeMillis();
				}
			}

			if (this.state.weAreInterested) {
				fillRequestQueue();
			}

			if (writeable) {
				int bytesWritten = this.outboundQueue.sendData();
				this.peerStatistics.protocolBytesSent.add (bytesWritten);
			}

		} catch (IOException e) {

			close();

		}

		if (this.peerServices != null) {
			this.peerServices.unlock();
		}

	}


	/**
	 * Determines whether a block descriptor points to a valid region of the PieceDatabase and is no
	 * larger than the maximum allowed request
	 *
	 * @param blockDescriptor The descriptor to validate
	 * @return {@code true} if the descriptor is valid, otherwise {@code false}
	 */
	private boolean validateBlockDescriptor (BlockDescriptor blockDescriptor) {

		if (
			   (blockDescriptor.getPieceNumber() >= 0)
			&& (blockDescriptor.getPieceNumber () < this.pieceDatabase.getStorageDescriptor().getNumberOfPieces())
			&& (blockDescriptor.getOffset() >= 0
			&& (blockDescriptor.getLength() > 0))
			&& (blockDescriptor.getLength() <= PeerProtocolConstants.MAXIMUM_BLOCK_LENGTH)
			&& ((blockDescriptor.getOffset() + blockDescriptor.getLength()) <= this.pieceDatabase.getStorageDescriptor().getPieceLength (blockDescriptor.getPieceNumber())))
		{
			return true;
		}

		return false;

	}


	/**
	 * Generate and send appropriate Allowed Fast messages to the remotepeer
	 */
	private void generateAndSendAllowedFastSet() {

		byte[] remoteAddressBytes = this.connection.getRemoteAddress().getAddress();

		try {

			if (remoteAddressBytes.length == 4) {
				Set<Integer> allowedFastSet = new HashSet<Integer>();
				int numberOfPieces = this.pieceDatabase.getStorageDescriptor().getNumberOfPieces();

				remoteAddressBytes[3] = 0;
				byte[] infoHashBytes = this.pieceDatabase.getInfo().getHash().getBytes();
				byte[] hash = new byte[20];

				MessageDigest digest = MessageDigest.getInstance ("SHA");
				digest.update (remoteAddressBytes, 0, 4);
				digest.update (infoHashBytes, 0, infoHashBytes.length);
				digest.digest (hash, 0, 20);

				int numberAllowedFast = Math.min (PeerProtocolConstants.ALLOWED_FAST_THRESHOLD, numberOfPieces);

				while (allowedFastSet.size() < numberAllowedFast) {

					for (int i = 0; i < 5 && allowedFastSet.size() < numberAllowedFast; i++) {
						int j = i * 4;
						long y = (
								    (((long)hash[j] & 0xff) << 24) +
								  + ((hash[j+1] & 0xff) << 16) +
								  + ((hash[j+2] & 0xff) << 8) +
								  + (hash[j+3] & 0xff)
								) % numberOfPieces;
						allowedFastSet.add ((int)y);
					}

					digest.reset();
					digest.update (hash, 0, hash.length);
					digest.digest (hash, 0, 20);

				}

				this.outboundQueue.sendAllowedFastMessages (allowedFastSet);
			}

		} catch (GeneralSecurityException e) {
			// Shouldn't happen
			throw new InternalError (e.getMessage());
		}

	}


	/**
	 * Fills the request queue to the remote peer. If the {@link PeerServices} cannot supply any
	 * requests and there are none pending in the {@link PeerOutboundQueue}, signals the remote peer
	 * that we are not interested and updates our interest status
	 */
	private void fillRequestQueue() {

		int numRequests = this.outboundQueue.getRequestsNeeded();

		if (numRequests > 0) {
			List<BlockDescriptor> requests = this.peerServices.getRequests (this, numRequests, this.state.theyAreChoking);
			if (requests.size() > 0) {
				this.outboundQueue.sendRequestMessages (requests);
			} else {
				if (!this.state.theyAreChoking && !this.outboundQueue.hasOutstandingRequests()) {
					this.state.weAreInterested = false;
					this.outboundQueue.sendInterestedMessage (false);
				}
			}
		}

	}


	/**
	 * @param peerServices The peer services
	 * @param connection The connection through which to send and receive messages
	 * @param remotePeerID The remote peer ID
	 * @param parentStatistics The parent aggregate PeerStatistics for the whole peer set
	 * @param pieceDatabase The piece database
	 * @param fastExtensionEnabled {@code true} if the fast extension is enabled
	 * @param extensionProtocolEnabled {@code true} if the extension protocol is enabled
	 */
	public PeerHandler (PeerServices peerServices, Connection connection, PeerID remotePeerID, PeerStatistics parentStatistics, PieceDatabase pieceDatabase,
			boolean fastExtensionEnabled, boolean extensionProtocolEnabled)
	{

		this.peerServices = peerServices;
		this.connection = connection;
		this.state.remotePeerID = remotePeerID;
		this.state.fastExtensionEnabled = fastExtensionEnabled;
		this.state.extensionProtocolEnabled = extensionProtocolEnabled;
		this.protocolParser = new PeerProtocolParser (this, fastExtensionEnabled, extensionProtocolEnabled);
		this.peerStatistics = new PeerStatistics (parentStatistics);
		this.pieceDatabase = pieceDatabase;
		this.connection.setListener (this);

		StorageDescriptor initialDescriptor = this.pieceDatabase.getInfo().getStorageDescriptor();
		this.state.remoteBitField = new BitField (initialDescriptor.getNumberOfPieces());
		this.state.remoteView = initialDescriptor;
		this.outboundQueue = new PeerOutboundQueue (this.connection, this.pieceDatabase, this.peerStatistics.blockBytesSent);

		// Send bitfield
		BitField bitField = this.pieceDatabase.getPresentPieces();
		if (this.pieceDatabase.getInfo().getPieceStyle() == PieceStyle.ELASTIC) {
			this.outboundQueue.sendHaveNoneMessage();
		} else {
			if (this.state.fastExtensionEnabled) {
				int pieceCount = bitField.cardinality();
				if (pieceCount == 0) {
					this.outboundQueue.sendHaveNoneMessage();
				} else if (pieceCount == this.pieceDatabase.getStorageDescriptor().getNumberOfPieces()) {
					this.outboundQueue.sendHaveAllMessage();
				} else {
					this.outboundQueue.sendBitfieldMessage (bitField);
				}
			} else {
				if (bitField.cardinality() > 0) {
					this.outboundQueue.sendBitfieldMessage (bitField);
				}
			}
		}

		if (this.pieceDatabase.getInfo().getPieceStyle() == PieceStyle.ELASTIC) {
			this.outboundQueue.sendExtensionHandshake (new HashSet<String> (Arrays.asList (PeerProtocolConstants.EXTENSION_ELASTIC)), null, null);
			if (this.pieceDatabase.getStorageDescriptor().getLength() > this.pieceDatabase.getInfo().getStorageDescriptor().getLength()) {
				this.outboundQueue.sendElasticSignatureMessage (this.pieceDatabase.getViewSignature (this.pieceDatabase.getStorageDescriptor().getLength()));
			}
			this.outboundQueue.sendElasticBitfieldMessage (this.pieceDatabase.getPresentPieces());
		} else if (this.pieceDatabase.getInfo().getPieceStyle() == PieceStyle.MERKLE) {
			this.outboundQueue.sendExtensionHandshake (new HashSet<String> (Arrays.asList (PeerProtocolConstants.EXTENSION_MERKLE)), null, null);
		};

		if (this.state.extensionProtocolEnabled) {
			this.peerServices.offerExtensionsToPeer (this);
		}

	}


}