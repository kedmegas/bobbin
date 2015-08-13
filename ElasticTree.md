# Elastic Hash Tree #

An elastic hash tree is a binary [hash tree](http://en.wikipedia.org/wiki/Hash_tree) that allows extension of the hashed data, can be efficiently queried and updated, and is compactly represented in memory.

An elastic hash tree consists of one or more **views**, each corresponding to a particular length of hashed data and containing mutable hash nodes that are only valid for that view, and a single **immutable set** of hash nodes that are valid for all views.

The nodes of an elastic hash tree are numbered by [postorder traversal](http://en.wikipedia.org/wiki/Postorder)

## Implementation ##

See http://code.google.com/p/bobbin/source/browse/#svn/trunk/Bobbin/src/org/itadaki/bobbin/util/elastictree

## Explanation ##

  * Yellow nodes are the **immutable set**
  * Orange nodes are **mutable view nodes**
  * Blue nodes are **filler nodes**. A filler leaf node is defined to have a hash of all zeroes

### Example 1 ###
A view modeling data of length 950, with a piece size of 100, has 9 immutable leaves and 1 mutable leaf

<img src='http://bobbin.googlecode.com/svn/wiki/postorder_tree_example1.png'>

Leaves 0 .. 8 (node indices 0, 1, 3, 4, 7, 8, 10, 11, 15) that each represent a complete piece are valid for any view that require them. Leaf 9 (node index 16) is only valid for this view; a view of differing length will instead use a filler node (for a view of less than 10 leaves), a different mutable node (for a different view of 10 leaves), or the node from the immutable set (for a view of more than 10 leaves).<br>
<br>
Note the following properties that are true for a view of any length :<br>
<pre><code>Property : The immutable set forms a contiguous list of node indices<br>
</code></pre>

<pre><code>Property : Mutable view nodes form a single path from the root<br>
</code></pre>
(The view path is not always the length of the full height of the tree - see the following example).<br>
<br>
These two properties form the basis of the compact representation of the tree.<br>
<br>
<pre><code>Property : Existing node indices do not change when more leaves are added, even if the height of the tree changes as a result<br>
</code></pre>

This property ensures that extending the tree is a time efficient operation, as the immutable set does not have to be rewritten for different length views.<br>
<br>
<br>
<h3>Example 2</h3>
A view modeling data of length 1000, with a piece size of 100, has 10 immutable leaves and 0 mutable leaves<br>
<br>
<img src='http://bobbin.googlecode.com/svn/wiki/postorder_tree_example2.png'>

Although there are no mutable leaves in this view, there are mutable nodes that are the ancestor of both immutable and filler nodes. When the last leaf in the view (the <b>view leaf</b>) is immutable, immutable nodes continue as far as can be moved up-left. A view of exactly 2<sup>n</sup> complete leaves will therefore contain no mutable nodes.<br>
<br>
<br>
<br>
<h2>Tree properties</h2>

<ul><li>An array of n hashes is modelled as the leaves of a perfect binary tree of height <code>ceil(log2(n))</code>, containing <code>2^height</code> leaves, and <code>2 * leaves - 1</code> total nodes<br>
</li><li>The nodes of the tree are numbered according to their sequence in a post order traversal, with zero being the first node (the first leaf)<br>
</li><li>The tree is considered to grow upwards from the leaves, such that for any leaf <code>e</code>, <code>height(e) = 0</code>
</li><li>Leaves with a leaf number greater than <code>n - 1</code> are filler leaves, considered to have a hash of zero<br>
</li><li>A filler node f at a given height within the tree has the following identity : <code>filler(0) = 0 ; filler(height(f)) = hash(filler(height(f)-1),filler(height(f)-1))</code>
</li><li>The root node of the tree is always the last numbered node, with node index <code>2 * leaves - 2</code>
</li><li>The node index of a leaf number <code>i</code> has the following identity: <code>nodeindex(i) = (2 * i) + ones(i)</code>, where <code>ones()</code> counts the number of set bits in an integer<br>
</li><li>A leaf with a leaf number <code>i</code> is a left sibling if <code>i % 1 == 0</code>, otherwise it is a right sibling<br>
</li><li>The parent of a left sibling <code>ls</code> is <code>ls + 2^(height(ls) + 1)</code>
</li><li>The parent of a right sibling <code>rs</code> is <code>rs + 1</code>
</li><li>The path from a leaf with a leaf index of <code>i</code> to the root node is contained within the bits of <code>i</code> starting with the smallest; at each point on the path, the node is a left sibling if the next bit is 0, otherwise it is a right sibling<br>
</li><li>Given a view of length <code>b</code>, with a piece size <code>p</code> and a highest non-filler leaf number <code>n</code>;<br>
<ul><li>For a given leaf node index <code>x</code>, let <code>path(x)</code> be the set of nodes on the path between <code>x</code> and the root<br>
</li><li>Let <code>trailingOnes(x)</code> be the number of bits in <code>x</code>, starting at the smallest, that are set before the first <code>0</code> is reached<br>
</li><li>If <code>(b % p) &gt; 0</code>, tree nodes <code>0 .. nodeindex(n) - 1</code> are immutable; tree nodes in <code>path(nodeindex(n))</code> are mutable and will change if <code>b</code> grows<br>
</li><li>If <code>(b % p) == 0</code>, tree nodes <code>0 .. (nodeindex(n) - 1 + trailingOnes(n))</code> are immutable and will not change even if b grows; tree nodes in <code>path(nodeindex(n))</code> that are greater than the final immutable node are mutable and will change if <code>b</code> grows