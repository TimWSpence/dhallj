package org.dhallj.core.binary.cbor;

import org.dhallj.core.binary.cbor.CBORExpression.Constants.AdditionalInfo;
import org.dhallj.core.binary.cbor.CBORExpression.Constants.MajorType;

import java.math.BigInteger;
import java.nio.charset.Charset;

/**
 * An implementation of enough of the CBOR spec to cope with decoding the CBOR values we need for
 * Dhall
 */
public abstract class CBORDecoder {

  public <R> R nextSymbol(Visitor<R> visitor) {
    byte b = this.read();
    switch (MajorType.fromByte(b)) {
      case UNSIGNED_INTEGER:
        return visitor.onUnsignedInteger(readUnsignedInteger(b));
      case NEGATIVE_INTEGER:
        return visitor.onNegativeInteger(readNegativeInteger(b));
      case BYTE_STRING:
        return visitor.onByteString(readByteString(b));
      case TEXT_STRING:
        return visitor.onTextString(readTextString(b));
      case ARRAY:
        return visitor.onArray(readArrayStart(b));
      case MAP:
        return visitor.onMap(readMapStart(b));
      case SEMANTIC_TAG:
        throw new RuntimeException("TODO");
      case PRIMITIVE:
        return readPrimitive(b, visitor);
      default:
        throw new RuntimeException(String.format("Invalid CBOR major type %d", b));
    }
  }

  protected abstract byte read();

  protected abstract byte peek();

  protected abstract byte[] read(int count);

  public BigInteger readUnsignedInteger() {
    return null;
  }

  private BigInteger readUnsignedInteger(byte b) {
    AdditionalInfo info = AdditionalInfo.fromByte(b);
    return readBigInteger(info, b);
  }

  private BigInteger readNegativeInteger(byte b) {
    AdditionalInfo info = AdditionalInfo.fromByte(b);
    return BigInteger.valueOf(-1).subtract(readBigInteger(info, b));
  }

  private byte[] readByteString(byte b) {
    AdditionalInfo info = AdditionalInfo.fromByte(b);
    BigInteger length = readBigInteger(info, b);
    if (length.compareTo(BigInteger.ZERO) < 0) {
      throw new RuntimeException("Indefinite byte string not needed for Dhall");
    } else {
      // We don't handle the case where the length is > Integer.MaxValue
      return this.read(length.intValue());
    }
  }

  private String readTextString(byte b) {
    AdditionalInfo info = AdditionalInfo.fromByte(b);
    BigInteger length = readBigInteger(info, b);
    if (length.compareTo(BigInteger.ZERO) < 0) {
      // Indefinite length - do we need this for Dhall?
      throw new RuntimeException("Indefinite text string not needed for Dhall");
    } else {
      // We don't handle the case where the length is > Integer.MaxValue
      return new String(this.read(length.intValue()), Charset.forName("UTF-8"));
    }
  }

  private BigInteger readArrayStart(byte b) {
    AdditionalInfo info = AdditionalInfo.fromByte(b);
    BigInteger length = readBigInteger(info, b);
    if (length.compareTo(BigInteger.ZERO) < 0) {
      throw new RuntimeException("Indefinite array not needed for Dhall");
    } else {
      // We don't handle the case where the length is > Integer.MaxValue
      return length;
    }
  }

  private BigInteger readMapStart(byte b) {
    AdditionalInfo info = AdditionalInfo.fromByte(b);
    BigInteger length = readBigInteger(info, b);
    if (length.compareTo(BigInteger.ZERO) < 0) {
      throw new RuntimeException("Indefinite array not needed for Dhall");
    } else {
      return length;
    }
  }

  private CBORExpression readSemanticTag(byte b) {
    return null;
  }

  private <R> R readPrimitive(byte b, Visitor<R> visitor) {
    int value = b & 31;
    if (0 <= value && value <= 19) {
      throw new RuntimeException(String.format("Primitive %d is unassigned", value));
    } else if (value == 20) {
      return visitor.onFalse();
    } else if (value == 21) {
      return visitor.onTrue();
    } else if (value == 22) {
      return visitor.onNull();
    } else if (value == 23) {
      throw new RuntimeException(String.format("Primitive %d is unassigned", value));
    } else if (value == 24) {
      throw new RuntimeException("Simple value not needed for Dhall");
    } else if (value == 25) {
      // https://github.com/c-rack/cbor-java/blob/master/src/main/java/co/nstant/in/cbor/decoder/HalfPrecisionFloatDecoder.java
      int bits = 0;
      for (int i = 0; i < 2; i++) {
        int next = this.read() & 0xff;
        bits <<= 8;
        bits |= next;
      }

      int s = (bits & 0x8000) >> 15;
      int e = (bits & 0x7C00) >> 10;
      int f = bits & 0x03FF;

      float result = 0;
      if (e == 0) {
        result = (float) ((s != 0 ? -1 : 1) * Math.pow(2, -14) * (f / Math.pow(2, 10)));
      } else if (e == 0x1F) {
        result = f != 0 ? Float.NaN : (s != 0 ? -1 : 1) * Float.POSITIVE_INFINITY;
      } else {
        result = (float) ((s != 0 ? -1 : 1) * Math.pow(2, e - 15) * (1 + f / Math.pow(2, 10)));
      }
      return visitor.onHalfFloat(result);
    } else if (value == 26) {
      int result = 0;
      for (int i = 0; i < 4; i++) {
        int next = this.read() & 0xff;
        result <<= 8;
        result |= next;
      }
      return visitor.onSingleFloat(Float.intBitsToFloat(result));
    } else if (value == 27) {
      long result = 0;
      for (int i = 0; i < 8; i++) {
        int next = this.read() & 0xff;
        result <<= 8;
        result |= next;
      }
      return visitor.onDoubleFloat(Double.longBitsToDouble(result));
    } else if (28 <= value && value <= 30) {
      throw new RuntimeException(String.format("Primitive %d is unassigned", value));
    } else if (value == 31) {
      throw new RuntimeException("Break stop code not needed for Dhall");
    } else {
      throw new RuntimeException(String.format("Primitive %d is not valid", value));
    }
  }

  private BigInteger readBigInteger(AdditionalInfo info, byte first) {
    switch (info) {
      case DIRECT:
        return BigInteger.valueOf(first & 31);
      case ONE_BYTE:
        return readBigInteger(1);
      case TWO_BYTES:
        return readBigInteger(2);
      case FOUR_BYTES:
        return readBigInteger(4);
      case EIGHT_BYTES:
        return readBigInteger(8);
      case RESERVED:
        throw new RuntimeException("Additional info RESERVED should not require reading a uintXX");
      case INDEFINITE:
        return BigInteger.valueOf(-1);
    }
    throw new RuntimeException("Why does Java not have exhaustivity checking?");
  }

  private BigInteger readBigInteger(int numBytes) {
    BigInteger result = BigInteger.ZERO;
    for (int i = 0; i < numBytes; i++) {
      int next = this.read() & 0xff;
      result = result.shiftLeft(8).or(BigInteger.valueOf(next));
    }
    return result;
  }

  private Long readLength() {
    return 0L;
  }

  public static final class ByteArrayCBORDecoder extends CBORDecoder {
    private final byte[] bytes;
    private int cursor = 0;

    public ByteArrayCBORDecoder(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    protected byte read() {
      return this.bytes[this.cursor++];
    }

    @Override
    protected byte peek() {
      return this.bytes[this.cursor];
    }

    @Override
    protected byte[] read(int count) {
      byte[] bs = new byte[count];

      System.arraycopy(bytes, this.cursor, bs, 0, count);
      this.cursor += count;

      return bs;
    }
  }
}
