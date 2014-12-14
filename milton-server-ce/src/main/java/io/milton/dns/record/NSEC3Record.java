/*  * Copyright (c) 2005 Brian Wellington (bwelling@xbill.org)  *   * Copied from the DnsJava project  *  * This program is free software: you can redistribute it and/or modify  * it under the terms of the GNU Affero General Public License as published by  * the Free Software Foundation, either version 3 of the License, or  * (at your option) any later version.  * This program is distributed in the hope that it will be useful,  * but WITHOUT ANY WARRANTY; without even the implied warranty of  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the  * GNU General Public License for more details.  * You should have received a copy of the GNU General Public License  * along with this program.  If not, see <http://www.gnu.org/licenses/>.  */

package io.milton.dns.record;

import io.milton.dns.Name;
import io.milton.dns.utils.base16;
import io.milton.dns.utils.base32;

import java.io.*;
import java.security.*;


/**
 * Next SECure name 3 - this record contains the next hashed name in an
 * ordered list of hashed names in the zone, and a set of types for which
 * records exist for this name. The presence of this record in a response
 * signifies a negative response from a DNSSEC-signed zone.
 * 
 * This replaces the NSEC and NXT records, when used.
 * 
 * @author Brian Wellington
 * @author David Blacka
 */

public class NSEC3Record extends Record {

public static class Flags {
	/**
	 * NSEC3 flags identifiers.
	 */

	private Flags() {}

	/** Unsigned delegation are not included in the NSEC3 chain.
	 *
	 */
	public static final int OPT_OUT = 0x01;
}

public static class Digest {
	private Digest() {}

	/** SHA-1 */
	public static final int SHA1 = 1;
}

public static final int SHA1_DIGEST_ID = Digest.SHA1;

private static final long serialVersionUID = -7123504635968932855L;

private int hashAlg;
private int flags;
private int iterations;
private byte [] salt;
private byte [] next;
private TypeBitmap types;

private static final base32 b32 = new base32(base32.Alphabet.BASE32HEX,
					     false, false);

NSEC3Record() {}

Record getObject() {
	return new NSEC3Record();
}

/**
 * Creates an NSEC3 record from the given data.
 * 
 * @param name The ownername of the NSEC3 record (base32'd hash plus zonename).
 * @param dclass The class.
 * @param ttl The TTL.
 * @param hashAlg The hash algorithm.
 * @param flags The value of the flags field.
 * @param iterations The number of hash iterations.
 * @param salt The salt to use (may be null).
 * @param next The next hash (may not be null).
 * @param types The types present at the original ownername.
 */
public NSEC3Record(Name name, int dclass, long ttl, int hashAlg,
		   int flags, int iterations, byte [] salt, byte [] next,
		   int [] types)
{
	super(name, Type.NSEC3, dclass, ttl);
	this.hashAlg = checkU8("hashAlg", hashAlg);
	this.flags = checkU8("flags", flags);
	this.iterations = checkU16("iterations", iterations);

	if (salt != null) {
		if (salt.length > 255)
			throw new IllegalArgumentException("Invalid salt");
		if (salt.length > 0) {
			this.salt = new byte[salt.length];
			System.arraycopy(salt, 0, this.salt, 0, salt.length);
		}
	}

	if (next.length > 255) {
		throw new IllegalArgumentException("Invalid next hash");
	}
	this.next = new byte[next.length];
	System.arraycopy(next, 0, this.next, 0, next.length);
	this.types = new TypeBitmap(types);
}

void
rrFromWire(DNSInput in) throws IOException {
	hashAlg = in.readU8();
	flags = in.readU8();
	iterations = in.readU16();

	int salt_length = in.readU8();
	if (salt_length > 0)
		salt = in.readByteArray(salt_length);
	else
		salt = null;

	int next_length = in.readU8();
	next = in.readByteArray(next_length);
	types = new TypeBitmap(in);
}

void
rrToWire(DNSOutput out, Compression c, boolean canonical) {
	out.writeU8(hashAlg);
	out.writeU8(flags);
	out.writeU16(iterations);

	if (salt != null) {
		out.writeU8(salt.length);
		out.writeByteArray(salt);
	} else
		out.writeU8(0);

	out.writeU8(next.length);
	out.writeByteArray(next);
	types.toWire(out);
}

void
rdataFromString(Tokenizer st, Name origin) throws IOException {
	hashAlg = st.getUInt8();
	flags = st.getUInt8();
	iterations = st.getUInt16();

	String s = st.getString();
	if (s.equals("-"))
		salt = null;
	else {
		st.unget();
		salt = st.getHexString();
		if (salt.length > 255)
			throw st.exception("salt value too long");
	}

	next = st.getBase32String(b32);
	types = new TypeBitmap(st);
}

/** Converts rdata to a String */
String
rrToString() {
	StringBuilder sb = new StringBuilder();
	sb.append(hashAlg);
	sb.append(' ');
	sb.append(flags);
	sb.append(' ');
	sb.append(iterations);
	sb.append(' ');
	if (salt == null)
		sb.append('-');
	else
		sb.append(base16.toString(salt));
	sb.append(' ');
	sb.append(b32.toString(next));

	if (!types.empty()) {
		sb.append(' ');
		sb.append(types.toString());
	}

	return sb.toString();
}

/** Returns the hash algorithm */
public int
getHashAlgorithm() {
	return hashAlg;
}

/** Returns the flags */
public int
getFlags() {
	return flags;
}

/** Returns the number of iterations */
public int
getIterations() {
	return iterations;
}

/** Returns the salt */
public byte []
getSalt()
{
	return salt;
}

/** Returns the next hash */
public byte []
getNext() {
	return next;
}

  /** Returns the set of types defined for this name */
public int []
getTypes() {
	return types.toArray();
}

/** Returns whether a specific type is in the set of types. */
public boolean
hasType(int type)
{
	return types.contains(type);
}

static byte []
hashName(Name name, int hashAlg, int iterations, byte [] salt)
throws NoSuchAlgorithmException
{
	MessageDigest digest;
	switch (hashAlg) {
	case Digest.SHA1:
		digest = MessageDigest.getInstance("sha-1");
		break;
	default:
		throw new NoSuchAlgorithmException("Unknown NSEC3 algorithm" +
						   "identifier: " +
						   hashAlg);
	}
	byte [] hash = null;
	for (int i = 0; i <= iterations; i++) {
		digest.reset();
		if (i == 0)
			digest.update(name.toWireCanonical());
		else
			digest.update(hash);
		if (salt != null)
			digest.update(salt);
		hash = digest.digest();
	}
	return hash;
}

/**
 * Hashes a name with the parameters of this NSEC3 record.
 * @param name The name to hash
 * @return The hashed version of the name
 * @throws NoSuchAlgorithmException The hash algorithm is unknown.
 */
public byte []
hashName(Name name) throws NoSuchAlgorithmException
{
	return hashName(name, hashAlg, iterations, salt);
}

}
