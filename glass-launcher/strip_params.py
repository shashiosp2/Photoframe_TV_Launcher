import os, struct, sys

def strip_method_parameters(class_path):
    with open(class_path, 'rb') as f:
        data = bytearray(f.read())
    
    magic, minor, major, cp_count = struct.unpack('>IHHH', data[:10])
    pos = 10
    cp = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1: # Utf8
            length = struct.unpack('>H', data[pos:pos+2])[0]
            pos += 2
            cp[i] = data[pos:pos+length].decode('utf-8', errors='ignore')
            pos += length
        elif tag in (3, 4): # Integer, Float
            pos += 4
        elif tag in (5, 6): # Long, Double
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f'Unknown tag {tag} at {pos}')
        i += 1
    
    pos += 6
    interfaces_count = struct.unpack('>H', data[pos:pos+2])[0]
    pos += 2 + interfaces_count * 2
    
    fields_count = struct.unpack('>H', data[pos:pos+2])[0]
    pos += 2
    for _ in range(fields_count):
        pos += 6
        attr_count = struct.unpack('>H', data[pos:pos+2])[0]
        pos += 2
        for _ in range(attr_count):
            length = struct.unpack('>I', data[pos+2:pos+6])[0]
            pos += 6 + length

    methods_count = struct.unpack('>H', data[pos:pos+2])[0]
    method_data_start = pos
    pos += 2
    
    new_methods = []
    for _ in range(methods_count):
        m_flags, m_name, m_desc = struct.unpack('>HHH', data[pos:pos+6])
        pos += 6
        attr_count = struct.unpack('>H', data[pos:pos+2])[0]
        pos += 2
        new_attrs = []
        for _ in range(attr_count):
            name_idx, length = struct.unpack('>HI', data[pos:pos+6])
            attr_bytes = data[pos:pos+6+length]
            pos += 6 + length
            if cp[name_idx] != 'MethodParameters':
                new_attrs.append(attr_bytes)
        m_bytes = struct.pack('>HHHH', m_flags, m_name, m_desc, len(new_attrs)) + b''.join(new_attrs)
        new_methods.append(m_bytes)

    class_attrs_start = pos
    class_attr_count = struct.unpack('>H', data[pos:pos+2])[0]
    pos += 2
    new_class_attrs = []
    for _ in range(class_attr_count):
        name_idx, length = struct.unpack('>HI', data[pos:pos+6])
        attr_bytes = data[pos:pos+6+length]
        pos += 6 + length
        if cp[name_idx] != 'MethodParameters':
            new_class_attrs.append(attr_bytes)
            
    final_data = data[:method_data_start] + struct.pack('>H', methods_count) + b''.join(new_methods) + struct.pack('>H', len(new_class_attrs)) + b''.join(new_class_attrs)
    with open(class_path, 'wb') as f:
        f.write(final_data)

target_dir = sys.argv[1]
count = 0
for root, _, files in os.walk(target_dir):
    for f in files:
        if f.endswith('.class'):
            strip_method_parameters(os.path.join(root, f))
            count += 1
print(f'Processed and stripped MethodParameters from {count} classes.')
