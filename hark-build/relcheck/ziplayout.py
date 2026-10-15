import sys,struct,zipfile
p=sys.argv[1]; d=open(p,'rb').read()
e=d.rfind(b'PK\x05\x06'); cd_size,cd_off,clen=struct.unpack('<IIH',d[e+12:e+22])
print('size',len(d),'EOCD at',e,'CD off',cd_off,'CD size',cd_size,'comment len',clen,'EOCD end ok',e+22+clen==len(d))
magic=d[cd_off-16:cd_off]; print('sig block magic before CD:',magic)
if magic==b'APK Sig Block 42':
    sz=struct.unpack('<Q',d[cd_off-24:cd_off-16])[0]; start=cd_off-sz-8
    print('sig block start',start,'size',sz,'size-at-start matches',struct.unpack('<Q',d[start:start+8])[0]==sz)
    q=start+8
    while q<cd_off-24:
        l,i=struct.unpack('<QI',d[q:q+12]); print('  pair id 0x%08x len %d'%(i,l-4)); q+=8+l
z=zipfile.ZipFile(p); il=z.infolist()
last=max(il,key=lambda i:i.header_offset)
print('entries',len(il),'zip64?',any(i.file_size>=0xFFFFFFFF or i.header_offset>=0xFFFFFFFF for i in il))
print('data-descriptor flag entries',sum(1 for i in il if i.flag_bits&8),'extra-field entries',sum(1 for i in il if i.extra))
print('first entries',[i.filename for i in il[:4]])
