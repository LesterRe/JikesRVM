/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */

package org.jikesrvm.classloader;

import java.io.DataInputStream;
import java.io.IOException;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.runtime.Statics;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Offset;

/**
 * Support code to parse a DataInputStream in the Java classfile format
 * and create the appropriate instance of an RVMClass or UnboxedType.
 * Also low-level support for our internal constant pool format.
 */
public class ClassFileReader implements Constants, ClassLoaderConstants {

  /**
   * Create an instance of a RVMClass.
   * @param typeRef the cannonical type reference for this type.
   * @param input the data stream from which to read the class's description.
   */
  static RVMClass readClass(TypeReference typeRef, DataInputStream input) throws ClassFormatError, IOException {

    if (RVMClass.classLoadingDisabled) {
      throw new RuntimeException("ClassLoading Disabled : " + typeRef);
    }

    if (VM.TraceClassLoading && VM.runningVM) {
      VM.sysWrite("RVMClass: (begin) load file " + typeRef.getName() + "\n");
    }

    int magic = input.readInt();
    if (magic != 0xCAFEBABE) {
      throw new ClassFormatError("bad magic number " + Integer.toHexString(magic));
    }

    // Get the class file version number and check to see if it is a version
    // that we support.
    int minor = input.readUnsignedShort();
    int major = input.readUnsignedShort();
    switch (major) {
      case 45:
      case 46:
      case 47:
      case 48:
      case 49: // we support all variants of these major versions so the minor number doesn't matter.
        break;
      case 50: // we only support up to 50.0 (ie Java 1.6.0)
        if (minor == 0) break;
      default:
        throw new UnsupportedClassVersionError("unsupported class file version " + major + "." + minor);
    }

    //
    // pass 1: read constant pool
    //
    int[] constantPool = new int[input.readUnsignedShort()];
    byte[] tmpTags = new byte[constantPool.length];

    // note: slot 0 is unused
    for (int i = 1; i < constantPool.length; i++) {
      tmpTags[i] = input.readByte();
      switch (tmpTags[i]) {
        case TAG_UTF: {
          byte[] utf = new byte[input.readUnsignedShort()];
          input.readFully(utf);
          int atomId = Atom.findOrCreateUtf8Atom(utf).getId();
          constantPool[i] = ClassFileReader.packCPEntry(ClassFileReader.CP_UTF, atomId);
          break;
        }
        case TAG_UNUSED:
          if (VM.VerifyAssertions) VM._assert(NOT_REACHED);
          break;

        case TAG_INT: {
          int literal = input.readInt();
          int offset = Statics.findOrCreateIntSizeLiteral(literal);
          constantPool[i] = ClassFileReader.packCPEntry(ClassFileReader.CP_INT, offset);
          break;
        }
        case TAG_FLOAT: {
          int literal = input.readInt();
          int offset = Statics.findOrCreateIntSizeLiteral(literal);
          constantPool[i] = ClassFileReader.packCPEntry(ClassFileReader.CP_FLOAT, offset);
          break;
        }
        case TAG_LONG: {
          long literal = input.readLong();
          int offset = Statics.findOrCreateLongSizeLiteral(literal);
          constantPool[i] = ClassFileReader.packCPEntry(ClassFileReader.CP_LONG, offset);
          i++;
          break;
        }
        case TAG_DOUBLE: {
          long literal = input.readLong();
          int offset = Statics.findOrCreateLongSizeLiteral(literal);
          constantPool[i] = ClassFileReader.packCPEntry(ClassFileReader.CP_DOUBLE, offset);
          i++;
          break;
        }
        case TAG_TYPEREF:
          constantPool[i] = input.readUnsignedShort();
          break;

        case TAG_STRING:
          constantPool[i] = input.readUnsignedShort();
          break;

        case TAG_FIELDREF:
        case TAG_METHODREF:
        case TAG_INTERFACE_METHODREF: {
          int classDescriptorIndex = input.readUnsignedShort();
          int memberNameAndDescriptorIndex = input.readUnsignedShort();
          constantPool[i] = ClassFileReader.packTempCPEntry(classDescriptorIndex, memberNameAndDescriptorIndex);
          break;
        }

        case TAG_MEMBERNAME_AND_DESCRIPTOR: {
          int memberNameIndex = input.readUnsignedShort();
          int descriptorIndex = input.readUnsignedShort();
          constantPool[i] = ClassFileReader.packTempCPEntry(memberNameIndex, descriptorIndex);
          break;
        }

        default:
          throw new ClassFormatError("bad constant pool");
      }
    }

    //
    // pass 2: post-process type and string constant pool entries
    // (we must do this in a second pass because of forward references)
    //
    try {
      for (int i = 1; i < constantPool.length; i++) {
        switch (tmpTags[i]) {
          case TAG_LONG:
          case TAG_DOUBLE:
            ++i;
            break;

          case TAG_TYPEREF: { // in: utf index
            Atom typeName = ClassFileReader.getUtf(constantPool, constantPool[i]);
            int typeRefId =
                TypeReference.findOrCreate(typeRef.getClassLoader(), typeName.descriptorFromClassName()).getId();
            constantPool[i] = ClassFileReader.packCPEntry(ClassFileReader.CP_CLASS, typeRefId);
            break;
          } // out: type reference id

          case TAG_STRING: { // in: utf index
            Atom literal = ClassFileReader.getUtf(constantPool, constantPool[i]);
            int offset = literal.getStringLiteralOffset();
            constantPool[i] = ClassFileReader.packCPEntry(ClassFileReader.CP_STRING, offset);
            break;
          } // out: jtoc slot number
        }
      }
    } catch (java.io.UTFDataFormatException x) {
      throw new ClassFormatError(x.toString());
    }

    //
    // pass 3: post-process type field and method constant pool entries
    //
    for (int i = 1; i < constantPool.length; i++) {
      switch (tmpTags[i]) {
        case TAG_LONG:
        case TAG_DOUBLE:
          ++i;
          break;

        case TAG_FIELDREF:
        case TAG_METHODREF:
        case TAG_INTERFACE_METHODREF: { // in: classname+membername+memberdescriptor indices
          int bits = constantPool[i];
          int classNameIndex = ClassFileReader.unpackTempCPIndex1(bits);
          int memberNameAndDescriptorIndex = ClassFileReader.unpackTempCPIndex2(bits);
          int memberNameAndDescriptorBits = constantPool[memberNameAndDescriptorIndex];
          int memberNameIndex = ClassFileReader.unpackTempCPIndex1(memberNameAndDescriptorBits);
          int memberDescriptorIndex = ClassFileReader.unpackTempCPIndex2(memberNameAndDescriptorBits);

          TypeReference tref = ClassFileReader.getTypeRef(constantPool, classNameIndex);
          Atom memberName = ClassFileReader.getUtf(constantPool, memberNameIndex);
          Atom memberDescriptor = ClassFileReader.getUtf(constantPool, memberDescriptorIndex);
          MemberReference mr = MemberReference.findOrCreate(tref, memberName, memberDescriptor);
          int mrId = mr.getId();
          constantPool[i] = ClassFileReader.packCPEntry(ClassFileReader.CP_MEMBER, mrId);
          break;
        } // out: MemberReference id
      }
    }

    short modifiers = input.readShort();
    int myTypeIndex = input.readUnsignedShort();
    TypeReference myTypeRef = ClassFileReader.getTypeRef(constantPool, myTypeIndex);
    if (myTypeRef != typeRef) {
      // eg. file contains a different class than would be
      // expected from its .class file name
      if (!VM.VerifyAssertions) {
        throw new ClassFormatError("expected class \"" +
                                   typeRef.getName() +
                                   "\" but found \"" +
                                   myTypeRef.getName() +
                                   "\"");
      } else {
        throw new ClassFormatError("expected class \"" +
                                   typeRef.getName() +
                                   "\" but found \"" +
                                   myTypeRef.getName() +
                                   "\"\n" + typeRef + " != " + myTypeRef);
      }
    }

    TypeReference superType = ClassFileReader.getTypeRef(constantPool, input.readUnsignedShort()); // possibly null
    RVMClass superClass = null;
    if (((modifiers & ACC_INTERFACE) == 0) && (superType != null)) {
      superClass = superType.resolve().asClass();
    }

    int numInterfaces = input.readUnsignedShort();
    RVMClass[] declaredInterfaces;
    if (numInterfaces == 0) {
      declaredInterfaces = RVMType.emptyVMClass;
    } else {
      declaredInterfaces = new RVMClass[numInterfaces];
      for (int i = 0; i < numInterfaces; ++i) {
        TypeReference inTR = ClassFileReader.getTypeRef(constantPool, input.readUnsignedShort());
        declaredInterfaces[i] = inTR.resolve().asClass();
      }
    }

    int numFields = input.readUnsignedShort();
    RVMField[] declaredFields;
    if (numFields == 0) {
      declaredFields = RVMType.emptyVMField;
    } else {
      declaredFields = new RVMField[numFields];
      for (int i = 0; i < numFields; i++) {
        short fmodifiers = input.readShort();
        Atom fieldName = ClassFileReader.getUtf(constantPool, input.readUnsignedShort());
        Atom fieldDescriptor = ClassFileReader.getUtf(constantPool, input.readUnsignedShort());
        MemberReference memRef = MemberReference.findOrCreate(typeRef, fieldName, fieldDescriptor);
        declaredFields[i] = RVMField.readField(typeRef, constantPool, memRef, fmodifiers, input);
      }
    }

    int numMethods = input.readUnsignedShort();
    RVMMethod[] declaredMethods;
    RVMMethod classInitializerMethod = null;
    if (numMethods == 0) {
      declaredMethods = RVMType.emptyVMMethod;
    } else {
      declaredMethods = new RVMMethod[numMethods];
      for (int i = 0; i < numMethods; i++) {
        short mmodifiers = input.readShort();
        Atom methodName = ClassFileReader.getUtf(constantPool, input.readUnsignedShort());
        Atom methodDescriptor = ClassFileReader.getUtf(constantPool, input.readUnsignedShort());
        MemberReference memRef = MemberReference.findOrCreate(typeRef, methodName, methodDescriptor);
        RVMMethod method = RVMMethod.readMethod(typeRef, constantPool, memRef, mmodifiers, input);
        declaredMethods[i] = method;
        if (method.isClassInitializer()) {
          classInitializerMethod = method;
        }
      }
    }
    TypeReference[] declaredClasses = null;
    Atom sourceName = null;
    TypeReference declaringClass = null;
    Atom signature = null;
    RVMAnnotation[] annotations = null;
    TypeReference enclosingClass = null;
    MethodReference enclosingMethod = null;
    // Read attributes.
    for (int i = 0, n = input.readUnsignedShort(); i < n; ++i) {
      Atom attName = ClassFileReader.getUtf(constantPool, input.readUnsignedShort());
      int attLength = input.readInt();

      // Class attributes
      if (attName == RVMClassLoader.sourceFileAttributeName && attLength == 2) {
        sourceName = ClassFileReader.getUtf(constantPool, input.readUnsignedShort());
      } else if (attName == RVMClassLoader.innerClassesAttributeName) {
        // Parse InnerClasses attribute, and use the information to populate
        // the list of declared member classes.  We do this so we can
        // support the java.lang.Class.getDeclaredClasses()
        // and java.lang.Class.getDeclaredClass methods.

        int numberOfClasses = input.readUnsignedShort();
        declaredClasses = new TypeReference[numberOfClasses];

        for (int j = 0; j < numberOfClasses; ++j) {
          int innerClassInfoIndex = input.readUnsignedShort();
          int outerClassInfoIndex = input.readUnsignedShort();
          int innerNameIndex = input.readUnsignedShort();
          int innerClassAccessFlags = input.readUnsignedShort();

          if (innerClassInfoIndex != 0 && outerClassInfoIndex == myTypeIndex && innerNameIndex != 0) {
            // This looks like a declared inner class.
            declaredClasses[j] = ClassFileReader.getTypeRef(constantPool, innerClassInfoIndex);
          }

          if (innerClassInfoIndex == myTypeIndex) {
            if (outerClassInfoIndex != 0) {
              declaringClass = ClassFileReader.getTypeRef(constantPool, outerClassInfoIndex);
              if (enclosingClass == null) {
                // TODO: is this the null test necessary?
                enclosingClass = declaringClass;
              }
            }
            if ((innerClassAccessFlags & (ACC_PRIVATE | ACC_PROTECTED)) != 0) {
              modifiers &= ~(ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED);
            }
            modifiers |= innerClassAccessFlags;
          }
        }
      } else if (attName == RVMClassLoader.syntheticAttributeName) {
        modifiers |= ACC_SYNTHETIC;
      } else if (attName == RVMClassLoader.enclosingMethodAttributeName) {
        int enclosingClassIndex = input.readUnsignedShort();
        enclosingClass = ClassFileReader.getTypeRef(constantPool, enclosingClassIndex);

        int enclosingMethodIndex = input.readUnsignedShort();
        if (enclosingMethodIndex != 0) {
          int memberNameIndex = constantPool[enclosingMethodIndex] >>> BITS_IN_SHORT;
          int memberDescriptorIndex = constantPool[enclosingMethodIndex] & ((1 << BITS_IN_SHORT) - 1);
          Atom memberName = ClassFileReader.getUtf(constantPool, memberNameIndex);
          Atom memberDescriptor = ClassFileReader.getUtf(constantPool, memberDescriptorIndex);
          enclosingMethod =
              MemberReference.findOrCreate(enclosingClass, memberName, memberDescriptor).asMethodReference();
        }
      } else if (attName == RVMClassLoader.signatureAttributeName) {
        signature = ClassFileReader.getUtf(constantPool, input.readUnsignedShort());
      } else if (attName == RVMClassLoader.runtimeVisibleAnnotationsAttributeName) {
        annotations = AnnotatedElement.readAnnotations(constantPool, input, typeRef.getClassLoader());
      } else {
        int skippedAmount = input.skipBytes(attLength);
        if (skippedAmount != attLength) {
          throw new IOException("Unexpected short skip");
        }
      }
    }

    return new RVMClass(typeRef,
                        constantPool,
                        modifiers,
                        superClass,
                        declaredInterfaces,
                        declaredFields,
                        declaredMethods,
                        declaredClasses,
                        declaringClass,
                        enclosingClass,
                        enclosingMethod,
                        sourceName,
                        classInitializerMethod,
                        signature,
                        annotations);
  }

  @Uninterruptible
  static int packCPEntry(byte type, int value) {
    return (type << 29) | (value & 0x1fffffff);
  }

  @Uninterruptible
  static byte unpackCPType(int cpValue) {
    return (byte) (cpValue >>> 29);
  }

  @Uninterruptible
  static int unpackSignedCPValue(int cpValue) {
    return (cpValue << 3) >> 3;
  }

  @Uninterruptible
  static int unpackUnsignedCPValue(int cpValue) {
    return cpValue & 0x1fffffff;
  }

  @Uninterruptible
  static boolean packedCPTypeIsClassType(int cpValue) {
    return (cpValue & (7 << 29)) == (ClassFileReader.CP_CLASS << 29);
  }

  @Uninterruptible
  static int packTempCPEntry(int index1, int index2) {
    return (index1 << 16) | (index2 & 0xffff);
  }

  @Uninterruptible
  static int unpackTempCPIndex1(int cpValue) {
    return cpValue >>> 16;
  }

  @Uninterruptible
  static int unpackTempCPIndex2(int cpValue) {
    return cpValue & 0xffff;
  }

  static int getLiteralSize(int[] constantPool, int constantPoolIndex) {
    int cpValue = constantPool[constantPoolIndex];
    switch (unpackCPType(cpValue)) {
      case ClassFileReader.CP_INT:
      case ClassFileReader.CP_FLOAT:
        return BYTES_IN_INT;
      case ClassFileReader.CP_LONG:
      case ClassFileReader.CP_DOUBLE:
        return BYTES_IN_LONG;
      case ClassFileReader.CP_CLASS:
      case ClassFileReader.CP_STRING:
        return BYTES_IN_ADDRESS;
      default:
        VM._assert(NOT_REACHED);
        return 0;
    }
  }

  /**
   * Get offset of a literal constant, in bytes.
   * Offset is with respect to virtual machine's "table of contents" (jtoc).
   */
  static Offset getLiteralOffset(int[] constantPool, int constantPoolIndex) {
    int cpValue = constantPool[constantPoolIndex];
    if (VM.VerifyAssertions) {
      int value = unpackSignedCPValue(cpValue);
      byte type = unpackCPType(cpValue);
      switch (type) {
        case ClassFileReader.CP_INT:
        case ClassFileReader.CP_FLOAT:
        case ClassFileReader.CP_LONG:
        case ClassFileReader.CP_DOUBLE:
        case ClassFileReader.CP_STRING:
          return Offset.fromIntSignExtend(value);
        case ClassFileReader.CP_CLASS: {
          int typeId = unpackUnsignedCPValue(cpValue);
          Class<?> literalAsClass = TypeReference.getTypeRef(typeId).resolve().getClassForType();
          return Offset.fromIntSignExtend(Statics.findOrCreateObjectLiteral(literalAsClass));
        }
        default:
          VM._assert(NOT_REACHED);
          return Offset.fromIntSignExtend(0xebad0ff5);
      }
    } else {
      if (packedCPTypeIsClassType(cpValue)) {
        int typeId = unpackUnsignedCPValue(cpValue);
        Class<?> literalAsClass = TypeReference.getTypeRef(typeId).resolve().getClassForType();
        return Offset.fromIntSignExtend(Statics.findOrCreateObjectLiteral(literalAsClass));
      } else {
        int value = unpackSignedCPValue(cpValue);
        return Offset.fromIntSignExtend(value);
      }
    }
  }

  /**
   * Get description of a literal constant.
   */
  static byte getLiteralDescription(int[] constantPool, int constantPoolIndex) {
    int cpValue = constantPool[constantPoolIndex];
    byte type = unpackCPType(cpValue);
    return type;
  }

  /**
   * Get contents of a "typeRef" constant pool entry.
   * @return type that was referenced
   */
  @Uninterruptible
  static TypeReference getTypeRef(int[] constantPool, int constantPoolIndex) {
    if (constantPoolIndex != 0) {
      int cpValue = constantPool[constantPoolIndex];
      if (VM.VerifyAssertions) VM._assert(unpackCPType(cpValue) == ClassFileReader.CP_CLASS);
      return TypeReference.getTypeRef(unpackUnsignedCPValue(cpValue));
    } else {
      return null;
    }
  }

  /**
   * Get contents of a "methodRef" constant pool entry.
   */
  @Uninterruptible
  static MethodReference getMethodRef(int[] constantPool, int constantPoolIndex) {
    int cpValue = constantPool[constantPoolIndex];
    if (VM.VerifyAssertions) VM._assert(unpackCPType(cpValue) == ClassFileReader.CP_MEMBER);
    return (MethodReference) MemberReference.getMemberRef(unpackUnsignedCPValue(cpValue));
  }

  /**
   * Get contents of a "methodRef" constant pool entry.
   */
  @Uninterruptible
  static FieldReference getFieldRef(int[] constantPool, int constantPoolIndex) {
    int cpValue = constantPool[constantPoolIndex];
    if (VM.VerifyAssertions) VM._assert(unpackCPType(cpValue) == ClassFileReader.CP_MEMBER);
    return (FieldReference) MemberReference.getMemberRef(unpackUnsignedCPValue(cpValue));
  }

  /**
   * Get contents of a "utf" from a constant pool entry.
   */
  @Uninterruptible
  static Atom getUtf(int[] constantPool, int constantPoolIndex) {
    int cpValue = constantPool[constantPoolIndex];
    if (VM.VerifyAssertions) VM._assert(unpackCPType(cpValue) == ClassFileReader.CP_UTF);
    return Atom.getAtom(unpackUnsignedCPValue(cpValue));
  }

  /** Constant pool entry for a UTF-8 encoded atom */
  public static final byte CP_UTF = 0;

}