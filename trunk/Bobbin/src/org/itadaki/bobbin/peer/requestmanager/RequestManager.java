package org.itadaki.bobbin.peer.requestmanager;

import java.util.List;

import org.itadaki.bobbin.peer.ManageablePeer;
import org.itadaki.bobbin.peer.PeerCoordinatorListener;
import org.itadaki.bobbin.torrentdb.BlockDescriptor;
import org.itadaki.bobbin.torrentdb.Piece;
import org.itadaki.bobbin.torrentdb.StorageDescriptor;
import org.itadaki.bobbin.torrentdb.ViewSignature;
import org.itadaki.bobbin.util.BitField;
import org.itadaki.bobbin.util.elastictree.HashChain;


/**
 * A manager to control the allocation of requests to peers and the handling of the resulting piece
 * blocks
 */
public interface RequestManager extends PeerCoordinatorListener {

	/**
	 * Adds a set of pieces to the available piece counts
	 *
	 * @param peer The peer to add pieces from
	 * @return {@code true} if the peer's bitfield contains any pieces that we are interested in,
	 *         otherwise {@code false}
	 */
	public boolean piecesAvailable (ManageablePeer peer);

	/**
	 * Adds a single piece to the available piece counts
	 *
	 * @param peer The peer that has the piece
	 * @param pieceNumber The piece to add
	 * @return {@code true} if we are interested in the given piece, otherwise {@code false}
	 */
	public boolean pieceAvailable (ManageablePeer peer, int pieceNumber);

	/**
	 * Indicates that the remote peer has Allowed Fast a given piece
	 *
	 * <p><b>Thread safety:</b> This method must be called with the peer context lock held
	 *
	 * @param peer The peer that Allowed Fast the piece
	 * @param pieceNumber The piece that was Allowed Fast. Must be a piece actually advertised by
	 *        the given peer
	 */
	public void setPieceAllowedFast (ManageablePeer peer, int pieceNumber);

	/**
	 * Indicates that the remotepeer has suggested a given piece
	 *
	 * @param peer The peer that suggested the piece
	 * @param pieceNumber The piece that was suggested. Must be a piece actually advertised by the
	 *        given peer
	 */
	public void setPieceSuggested (ManageablePeer peer, int pieceNumber);

	/**
	 * Allocates requests to the supplied peer. The requests are not guaranteed to be unique, and
	 * during the last few required pieces are highly likely to be duplicated.
	 *
	 * @param peer The peer to allocate requests for
	 * @param numRequests The number of requests to allocate
	 * @param allowedFastOnly If {@code true}, only pieces that have been marked Allowed Fast for
	 *        the given peer will be allocated
	 * @return A list of allocated {@code BlockDescriptor}s
	 */
	public List<BlockDescriptor> allocateRequests (ManageablePeer peer, int numRequests, boolean allowedFastOnly);

	/**
	 * Handles a received block, assembling the piece data if we have a complete set of blocks.
	 * 
	 * <p>If a piece is returned by this method and is successfully verified, a call to
	 * {@link #setPieceNotNeeded(int)} should subsequently be made
	 *
	 * @param peer The peer that sent the block
	 * @param descriptor The request corresponding to this block
	 * @param viewSignature The view signature corresponding to the hash chain, if supplied, or
	 *        {@code null}
	 * @param hashChain The hash chain of the piece, if supplied, or {@code null}
	 * @param block The data of the block
	 * @return The piece data if a piece was successfully assembled
	 */
	public Piece handleBlock (ManageablePeer peer, BlockDescriptor descriptor, ViewSignature viewSignature, HashChain hashChain, byte[] block);

	/**
	 * @param neededPieces The set of needed pieces
	 */
	public void setNeededPieces (BitField neededPieces);

	/**
	 * Sets a single piece as not needed. Any outstanding requests for blocks of the given piece are
	 * synchronously cancelled.
	 *
	 * @param pieceNumber The piece to set not needed
	 */
	public void setPieceNotNeeded (int pieceNumber);

	/**
	 * @return The number of pieces that are needed to complete the torrent
	 */
	public int getNeededPieceCount();

	/**
	 * Updates the RequestManager's view of the storage on an extension
	 *
	 * @param storageDescriptor The new StorageDescriptor
	 */
	public void extend (StorageDescriptor storageDescriptor);

}